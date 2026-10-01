package com.voicecontrol.infrastructure.persistence

import com.voicecontrol.domain.flow.AppSummary
import com.voicecontrol.domain.flow.Flow
import com.voicecontrol.domain.flow.FlowRepository
import com.voicecontrol.domain.flow.FlowStep
import com.voicecontrol.domain.flow.FlowVersion
import com.voicecontrol.domain.flow.FlowWithVersion
import com.voicecontrol.domain.flow.VersionSource
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID

class JdbcFlowRepository(private val db: Database) : FlowRepository {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private val stepsSerializer = ListSerializer(FlowStep.serializer())

    override suspend fun create(flow: Flow, firstVersion: FlowVersion): FlowWithVersion = db.tx {
        update(
            "INSERT INTO flows (id, user_id, app_package, name, screen_signature, current_version, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            flow.id, flow.userId, flow.appPackage, flow.name, flow.screenSignature, flow.currentVersion, flow.createdAt, flow.updatedAt,
        )
        insertVersion(firstVersion)
        FlowWithVersion(flow, firstVersion)
    }

    override suspend fun find(userId: UUID, flowId: UUID): FlowWithVersion? = db.tx { findIn(this, userId, flowId) }

    private fun findIn(c: Connection, userId: UUID, flowId: UUID): FlowWithVersion? =
        c.query(
            """
            SELECT f.*, v.version, v.steps, v.screen_signature AS v_signature, v.source, v.change_note, v.created_at AS v_created_at
            FROM flows f JOIN flow_versions v ON v.flow_id = f.id AND v.version = f.current_version
            WHERE f.id = ? AND f.user_id = ? AND f.deleted_at IS NULL
            """.trimIndent(),
            flowId, userId,
        ) { rs -> FlowWithVersion(toFlow(rs), toVersion(rs, "v_signature", "v_created_at")) }.firstOrNull()

    override suspend fun findBySignature(userId: UUID, appPackage: String, signature: String): FlowWithVersion? = db.tx {
        val id = query(
            "SELECT id FROM flows WHERE user_id = ? AND app_package = ? AND md5(screen_signature) = md5(?) AND screen_signature = ? AND deleted_at IS NULL",
            userId, appPackage, signature, signature,
        ) { it.uuid("id") }.firstOrNull() ?: return@tx null
        findIn(this, userId, id)
    }

    override suspend fun list(userId: UUID, appPackage: String?, limit: Int, offset: Int): List<Flow> = db.tx {
        if (appPackage == null) {
            query("SELECT * FROM flows WHERE user_id = ? AND deleted_at IS NULL ORDER BY updated_at DESC LIMIT ? OFFSET ?", userId, limit, offset, map = ::toFlow)
        } else {
            query(
                "SELECT * FROM flows WHERE user_id = ? AND app_package = ? AND deleted_at IS NULL ORDER BY updated_at DESC LIMIT ? OFFSET ?",
                userId, appPackage, limit, offset, map = ::toFlow,
            )
        }
    }

    override suspend fun versions(userId: UUID, flowId: UUID): List<FlowVersion> = db.tx {
        query(
            """
            SELECT v.*, v.screen_signature AS v_signature, v.created_at AS v_created_at FROM flow_versions v
            JOIN flows f ON f.id = v.flow_id WHERE v.flow_id = ? AND f.user_id = ? AND f.deleted_at IS NULL ORDER BY v.version DESC
            """.trimIndent(),
            flowId, userId,
        ) { toVersion(it, "v_signature", "v_created_at") }
    }

    override suspend fun version(userId: UUID, flowId: UUID, version: Int): FlowVersion? = db.tx {
        query(
            """
            SELECT v.*, v.screen_signature AS v_signature, v.created_at AS v_created_at FROM flow_versions v
            JOIN flows f ON f.id = v.flow_id WHERE v.flow_id = ? AND v.version = ? AND f.user_id = ? AND f.deleted_at IS NULL
            """.trimIndent(),
            flowId, version, userId,
        ) { toVersion(it, "v_signature", "v_created_at") }.firstOrNull()
    }

    override suspend fun addVersion(userId: UUID, flowId: UUID, expectedVersion: Int, name: String?, next: FlowVersion): FlowWithVersion? = db.tx {
        // Optimistic concurrency: only advance if nobody else did since the client loaded the flow.
        val updated = update(
            """
            UPDATE flows SET current_version = ?, name = COALESCE(?, name), updated_at = ?
            WHERE id = ? AND user_id = ? AND current_version = ? AND deleted_at IS NULL
            """.trimIndent(),
            next.version, name, next.createdAt, flowId, userId, expectedVersion,
        )
        if (updated == 0) return@tx null
        insertVersion(next)
        findIn(this, userId, flowId)
    }

    override suspend fun delete(userId: UUID, flowId: UUID): Boolean = db.tx {
        update("UPDATE flows SET deleted_at = now() WHERE id = ? AND user_id = ? AND deleted_at IS NULL", flowId, userId) > 0
    }

    override suspend fun apps(userId: UUID): List<AppSummary> = db.tx {
        query(
            "SELECT app_package, count(*) AS n, max(updated_at) AS last FROM flows WHERE user_id = ? AND deleted_at IS NULL GROUP BY app_package ORDER BY last DESC",
            userId,
        ) { AppSummary(it.getString("app_package"), it.getInt("n"), it.instant("last")) }
    }

    private fun Connection.insertVersion(v: FlowVersion) {
        update(
            "INSERT INTO flow_versions (flow_id, version, steps, screen_signature, source, change_note, created_at) VALUES (?, ?, ?::jsonb, ?, ?, ?, ?)",
            v.flowId, v.version, json.encodeToString(stepsSerializer, v.steps), v.screenSignature, v.source.name, v.changeNote, v.createdAt,
        )
    }

    private fun toFlow(rs: ResultSet) = Flow(
        id = rs.uuid("id"),
        userId = rs.uuid("user_id"),
        appPackage = rs.getString("app_package"),
        name = rs.getString("name"),
        screenSignature = rs.getString("screen_signature"),
        currentVersion = rs.getInt("current_version"),
        createdAt = rs.instant("created_at"),
        updatedAt = rs.instant("updated_at"),
    )

    private fun toVersion(rs: ResultSet, signatureColumn: String, createdColumn: String) = FlowVersion(
        flowId = rs.uuid(if (hasColumn(rs, "flow_id")) "flow_id" else "id"),
        version = rs.getInt("version"),
        steps = json.decodeFromString(stepsSerializer, rs.getString("steps")),
        screenSignature = rs.getString(signatureColumn),
        source = runCatching { VersionSource.valueOf(rs.getString("source")) }.getOrDefault(VersionSource.DEVICE),
        changeNote = rs.getString("change_note"),
        createdAt = rs.instant(createdColumn),
    )

    private fun hasColumn(rs: ResultSet, name: String): Boolean =
        (1..rs.metaData.columnCount).any { rs.metaData.getColumnLabel(it).equals(name, ignoreCase = true) }
}
