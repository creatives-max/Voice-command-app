package com.voicecontrol.infrastructure.persistence

import com.voicecontrol.domain.automation.Device
import com.voicecontrol.domain.automation.DeviceCommands
import com.voicecontrol.domain.automation.DeviceRepository
import com.voicecontrol.domain.automation.DueOutcome
import com.voicecontrol.domain.automation.DueTrigger
import com.voicecontrol.domain.automation.FlowTrigger
import com.voicecontrol.domain.automation.RunEvent
import com.voicecontrol.domain.automation.RunRequest
import com.voicecontrol.domain.automation.RunRequestRepository
import com.voicecontrol.domain.automation.RunRequestStatus
import com.voicecontrol.domain.automation.RunSource
import com.voicecontrol.domain.automation.TriggerRepository
import com.voicecontrol.domain.automation.TriggerType
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

class JdbcDeviceRepository(private val db: Database) : DeviceRepository {

    override suspend fun upsert(device: Device): Device = db.tx {
        query(
            """
            INSERT INTO devices (id, user_id, name, platform, app_version, remote_runs, last_seen_at, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET name = excluded.name, platform = excluded.platform, app_version = excluded.app_version,
                remote_runs = excluded.remote_runs, last_seen_at = excluded.last_seen_at
            WHERE devices.user_id = excluded.user_id
            RETURNING *
            """.trimIndent(),
            device.id, device.userId, device.name, device.platform, device.appVersion, device.remoteRuns, device.lastSeenAt, device.createdAt,
            map = ::toDevice,
        ).firstOrNull() ?: throw com.voicecontrol.domain.common.DomainException.Conflict("This device id is registered to another account")
    }

    override suspend fun find(userId: UUID, id: UUID): Device? = db.tx {
        query("SELECT * FROM devices WHERE user_id = ? AND id = ?", userId, id, map = ::toDevice).firstOrNull()
    }

    override suspend fun list(userId: UUID): List<Device> = db.tx {
        query("SELECT * FROM devices WHERE user_id = ? ORDER BY last_seen_at DESC", userId, map = ::toDevice)
    }

    override suspend fun update(userId: UUID, id: UUID, name: String?, remoteRuns: Boolean?): Device? = db.tx {
        query(
            "UPDATE devices SET name = coalesce(?, name), remote_runs = coalesce(?, remote_runs) WHERE user_id = ? AND id = ? RETURNING *",
            name, remoteRuns, userId, id, map = ::toDevice,
        ).firstOrNull()
    }

    override suspend fun touch(userId: UUID, id: UUID, at: Instant): Boolean = db.tx {
        update("UPDATE devices SET last_seen_at = ? WHERE user_id = ? AND id = ?", at, userId, id) > 0
    }

    override suspend fun delete(userId: UUID, id: UUID): Boolean = db.tx { update("DELETE FROM devices WHERE user_id = ? AND id = ?", userId, id) > 0 }

    override suspend fun mostRecent(userId: UUID): Device? = db.tx {
        query("SELECT * FROM devices WHERE user_id = ? AND remote_runs ORDER BY last_seen_at DESC LIMIT 1", userId, map = ::toDevice).firstOrNull()
    }
}

internal fun toDevice(rs: ResultSet) = Device(
    id = rs.uuid("id"),
    userId = rs.uuid("user_id"),
    name = rs.getString("name"),
    platform = rs.getString("platform"),
    appVersion = rs.getString("app_version"),
    remoteRuns = rs.getBoolean("remote_runs"),
    lastSeenAt = rs.instant("last_seen_at"),
    createdAt = rs.instant("created_at"),
)

private fun ResultSet.uuidOrNull(column: String): UUID? = getObject(column, UUID::class.java)
private fun ResultSet.instantOrNull(column: String): Instant? = getTimestamp(column)?.toInstant()

class JdbcTriggerRepository(private val db: Database) : TriggerRepository {

    override suspend fun insert(trigger: FlowTrigger): FlowTrigger = db.tx {
        query(
            """
            INSERT INTO flow_triggers (id, user_id, flow_id, type, enabled, cron, timezone, device_id, next_run_at, last_run_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING *
            """.trimIndent(),
            trigger.id, trigger.userId, trigger.flowId, trigger.type.name, trigger.enabled, trigger.cron, trigger.timezone, trigger.deviceId,
            trigger.nextRunAt, trigger.lastRunAt, trigger.createdAt, trigger.updatedAt,
            map = ::toTrigger,
        ).first()
    }

    override suspend fun update(trigger: FlowTrigger): FlowTrigger? = db.tx {
        query(
            """
            UPDATE flow_triggers SET type = ?, enabled = ?, cron = ?, timezone = ?, device_id = ?, next_run_at = ?, updated_at = ?
            WHERE user_id = ? AND id = ? RETURNING *
            """.trimIndent(),
            trigger.type.name, trigger.enabled, trigger.cron, trigger.timezone, trigger.deviceId, trigger.nextRunAt, trigger.updatedAt,
            trigger.userId, trigger.id,
            map = ::toTrigger,
        ).firstOrNull()
    }

    override suspend fun find(userId: UUID, id: UUID): FlowTrigger? = db.tx {
        query("SELECT * FROM flow_triggers WHERE user_id = ? AND id = ?", userId, id, map = ::toTrigger).firstOrNull()
    }

    override suspend fun listForFlow(userId: UUID, flowId: UUID): List<FlowTrigger> = db.tx {
        query("SELECT * FROM flow_triggers WHERE user_id = ? AND flow_id = ? ORDER BY created_at", userId, flowId, map = ::toTrigger)
    }

    override suspend fun listForUser(userId: UUID): List<FlowTrigger> = db.tx {
        query(
            """
            SELECT t.* FROM flow_triggers t JOIN flows f ON f.id = t.flow_id
            WHERE t.user_id = ? AND f.deleted_at IS NULL ORDER BY t.created_at
            """.trimIndent(),
            userId, map = ::toTrigger,
        )
    }

    override suspend fun delete(userId: UUID, id: UUID): Boolean = db.tx { update("DELETE FROM flow_triggers WHERE user_id = ? AND id = ?", userId, id) > 0 }

    override suspend fun claimDue(now: Instant, limit: Int, fire: (DueTrigger) -> DueOutcome): Int = db.tx {
        val due = query(
            """
            SELECT t.*, f.name AS flow_name, f.app_package AS flow_app,
                   coalesce(t.device_id, (SELECT d.id FROM devices d WHERE d.user_id = t.user_id AND d.remote_runs
                                           ORDER BY d.last_seen_at DESC LIMIT 1)) AS target_device
            FROM flow_triggers t JOIN flows f ON f.id = t.flow_id
            WHERE t.enabled AND t.type = 'SCHEDULE' AND t.next_run_at <= ? AND f.deleted_at IS NULL
            ORDER BY t.next_run_at
            LIMIT ?
            FOR UPDATE OF t SKIP LOCKED
            """.trimIndent(),
            now, limit,
        ) { rs -> DueTrigger(toTrigger(rs), rs.getString("flow_name"), rs.getString("flow_app"), rs.uuidOrNull("target_device")) }
        due.forEach { d ->
            val outcome = fire(d)
            update(
                "UPDATE flow_triggers SET next_run_at = ?, last_run_at = ?, enabled = ? WHERE id = ?",
                outcome.nextRunAt, now, outcome.nextRunAt != null, d.trigger.id,
            )
            outcome.request?.let { insertRequest(it, outcome.event) }
        }
        due.size
    }
}

internal fun toTrigger(rs: ResultSet) = FlowTrigger(
    id = rs.uuid("id"),
    userId = rs.uuid("user_id"),
    flowId = rs.uuid("flow_id"),
    type = TriggerType.valueOf(rs.getString("type")),
    enabled = rs.getBoolean("enabled"),
    cron = rs.getString("cron"),
    timezone = rs.getString("timezone"),
    deviceId = rs.uuidOrNull("device_id"),
    nextRunAt = rs.instantOrNull("next_run_at"),
    lastRunAt = rs.instantOrNull("last_run_at"),
    createdAt = rs.instant("created_at"),
    updatedAt = rs.instant("updated_at"),
)

internal fun Connection.insertRequest(r: RunRequest, firstEvent: String): RunRequest {
    val saved = query(
        """
        INSERT INTO run_requests (id, user_id, flow_id, flow_name, app_package, device_id, trigger_id, source, status, created_at, updated_at, expires_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING *
        """.trimIndent(),
        r.id, r.userId, r.flowId, r.flowName, r.appPackage, r.deviceId, r.triggerId, r.source.name, r.status.name, r.createdAt, r.updatedAt, r.expiresAt,
        map = ::toRequest,
    ).first()
    update("INSERT INTO run_request_events (request_id, at, kind, message) VALUES (?, ?, 'status', ?)", r.id, r.createdAt, firstEvent)
    return saved
}

internal fun toRequest(rs: ResultSet) = RunRequest(
    id = rs.uuid("id"),
    userId = rs.uuid("user_id"),
    flowId = rs.uuidOrNull("flow_id"),
    flowName = rs.getString("flow_name"),
    appPackage = rs.getString("app_package"),
    deviceId = rs.uuidOrNull("device_id"),
    triggerId = rs.uuidOrNull("trigger_id"),
    source = RunSource.valueOf(rs.getString("source")),
    status = RunRequestStatus.valueOf(rs.getString("status")),
    createdAt = rs.instant("created_at"),
    updatedAt = rs.instant("updated_at"),
    expiresAt = rs.instant("expires_at"),
)

class JdbcRunRequestRepository(private val db: Database) : RunRequestRepository {

    override suspend fun insert(request: RunRequest, firstEvent: String): RunRequest = db.tx { insertRequest(request, firstEvent) }

    override suspend fun find(userId: UUID, id: UUID): RunRequest? = db.tx {
        query("SELECT * FROM run_requests WHERE user_id = ? AND id = ?", userId, id, map = ::toRequest).firstOrNull()
    }

    override suspend fun list(userId: UUID, flowId: UUID?, limit: Int): List<RunRequest> = db.tx {
        if (flowId == null) {
            query("SELECT * FROM run_requests WHERE user_id = ? ORDER BY created_at DESC LIMIT ?", userId, limit, map = ::toRequest)
        } else {
            query("SELECT * FROM run_requests WHERE user_id = ? AND flow_id = ? ORDER BY created_at DESC LIMIT ?", userId, flowId, limit, map = ::toRequest)
        }
    }

    override suspend fun takeForDevice(userId: UUID, deviceId: UUID, now: Instant): DeviceCommands = db.tx {
        val run = query(
            """
            UPDATE run_requests SET status = 'DELIVERED', updated_at = ?
            WHERE id IN (SELECT id FROM run_requests WHERE user_id = ? AND device_id = ? AND status = 'PENDING' AND expires_at > ?
                         ORDER BY created_at FOR UPDATE SKIP LOCKED)
            RETURNING *
            """.trimIndent(),
            now, userId, deviceId, now, map = ::toRequest,
        ).sortedBy { it.createdAt }
        run.forEach { update("INSERT INTO run_request_events (request_id, at, kind, message) VALUES (?, ?, 'status', 'Delivered to the phone')", it.id, now) }
        val cancel = query(
            "SELECT * FROM run_requests WHERE user_id = ? AND device_id = ? AND status = 'CANCEL_REQUESTED'",
            userId, deviceId, map = ::toRequest,
        )
        DeviceCommands(run, cancel)
    }

    override suspend fun setStatus(userId: UUID, id: UUID, status: RunRequestStatus, now: Instant): RunRequest? = db.tx {
        query(
            "UPDATE run_requests SET status = ?, updated_at = ? WHERE user_id = ? AND id = ? RETURNING *",
            status.name, now, userId, id, map = ::toRequest,
        ).firstOrNull()
    }

    override suspend fun addEvents(requestId: UUID, events: List<Pair<String, String>>, now: Instant) = db.tx {
        prepareStatement("INSERT INTO run_request_events (request_id, at, kind, message) VALUES (?, ?, ?, ?)").use { ps ->
            events.forEach { (kind, message) ->
                ps.bind(arrayOf(requestId, now, kind, message))
                ps.addBatch()
            }
            ps.executeBatch()
        }
        Unit
    }

    override suspend fun events(userId: UUID, id: UUID, afterId: Long, limit: Int): List<RunEvent> = db.tx {
        query(
            """
            SELECT e.* FROM run_request_events e JOIN run_requests r ON r.id = e.request_id
            WHERE r.user_id = ? AND e.request_id = ? AND e.id > ? ORDER BY e.id LIMIT ?
            """.trimIndent(),
            userId, id, afterId, limit,
        ) { rs -> RunEvent(rs.getLong("id"), rs.uuid("request_id"), rs.instant("at"), rs.getString("kind"), rs.getString("message")) }
    }

    override suspend fun expire(now: Instant): Int = db.tx {
        val expired = query(
            "UPDATE run_requests SET status = 'EXPIRED', updated_at = ? WHERE status = 'PENDING' AND expires_at <= ? RETURNING id",
            now, now,
        ) { it.uuid("id") }
        expired.forEach { update("INSERT INTO run_request_events (request_id, at, kind, message) VALUES (?, ?, 'status', 'The phone did not pick this up in time')", it, now) }
        expired.size
    }
}
