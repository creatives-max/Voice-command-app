package com.voicecontrol.application.automation

import com.voicecontrol.domain.automation.Device
import com.voicecontrol.domain.automation.DeviceCommands
import com.voicecontrol.domain.automation.DeviceRepository
import com.voicecontrol.domain.automation.DueOutcome
import com.voicecontrol.domain.automation.FlowTrigger
import com.voicecontrol.domain.automation.RunEvent
import com.voicecontrol.domain.automation.RunRequest
import com.voicecontrol.domain.automation.RunRequestRepository
import com.voicecontrol.domain.automation.RunRequestStatus
import com.voicecontrol.domain.automation.RunSource
import com.voicecontrol.domain.automation.TriggerRepository
import com.voicecontrol.domain.automation.TriggerType
import com.voicecontrol.domain.common.DomainException
import com.voicecontrol.domain.flow.FlowRepository
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** Phones registered to an account. */
class DeviceService(private val devices: DeviceRepository, private val now: () -> Instant = Instant::now) {

    suspend fun register(userId: UUID, id: UUID, name: String, platform: String, appVersion: String?, remoteRuns: Boolean?): Device {
        val existing = devices.find(userId, id)
        val device = Device(
            id = id,
            userId = userId,
            name = validName(name),
            platform = platform.trim().take(20).ifEmpty { "android" },
            appVersion = appVersion?.trim()?.take(40),
            remoteRuns = remoteRuns ?: existing?.remoteRuns ?: true,
            lastSeenAt = now(),
            createdAt = existing?.createdAt ?: now(),
        )
        return devices.upsert(device)
    }

    suspend fun list(userId: UUID): List<Device> = devices.list(userId)

    suspend fun update(userId: UUID, id: UUID, name: String?, remoteRuns: Boolean?): Device =
        devices.update(userId, id, name?.let(::validName), remoteRuns) ?: throw DomainException.NotFound("Device not found")

    suspend fun delete(userId: UUID, id: UUID) {
        if (!devices.delete(userId, id)) throw DomainException.NotFound("Device not found")
    }

    private fun validName(name: String): String =
        name.trim().takeIf { it.length in 1..60 } ?: throw DomainException.Validation("Device name must be 1-60 characters")

    companion object {
        /** A phone that polled within this window is shown as online. */
        val ONLINE_WINDOW: Duration = Duration.ofSeconds(90)
    }
}

/** Input for creating or updating a trigger. */
data class TriggerInput(val type: TriggerType, val enabled: Boolean, val cron: String?, val timezone: String?, val deviceId: UUID?)

/** Per-flow triggers: when the app opens, or on a schedule. */
class TriggerService(
    private val triggers: TriggerRepository,
    private val flows: FlowRepository,
    private val devices: DeviceRepository,
    private val now: () -> Instant = Instant::now,
) {
    suspend fun listForFlow(userId: UUID, flowId: UUID): List<FlowTrigger> {
        flows.find(userId, flowId) ?: throw DomainException.NotFound("Flow not found")
        return triggers.listForFlow(userId, flowId)
    }

    suspend fun listForUser(userId: UUID): List<FlowTrigger> = triggers.listForUser(userId)

    suspend fun create(userId: UUID, flowId: UUID, input: TriggerInput): FlowTrigger {
        flows.find(userId, flowId) ?: throw DomainException.NotFound("Flow not found")
        if (triggers.listForFlow(userId, flowId).size >= MAX_PER_FLOW) {
            throw DomainException.Validation("A flow can have at most $MAX_PER_FLOW triggers")
        }
        val t = now()
        return triggers.insert(validated(userId, FlowTrigger(UUID.randomUUID(), userId, flowId, input.type, input.enabled, null, null, null, null, null, t, t), input))
    }

    suspend fun update(userId: UUID, id: UUID, input: TriggerInput): FlowTrigger {
        val existing = triggers.find(userId, id) ?: throw DomainException.NotFound("Trigger not found")
        return triggers.update(validated(userId, existing, input)) ?: throw DomainException.NotFound("Trigger not found")
    }

    suspend fun delete(userId: UUID, id: UUID) {
        if (!triggers.delete(userId, id)) throw DomainException.NotFound("Trigger not found")
    }

    private suspend fun validated(userId: UUID, base: FlowTrigger, input: TriggerInput): FlowTrigger {
        input.deviceId?.let { devices.find(userId, it) ?: throw DomainException.Validation("Unknown device") }
        return when (input.type) {
            TriggerType.APP_OPEN -> base.copy(type = input.type, enabled = input.enabled, cron = null, timezone = null, deviceId = input.deviceId, nextRunAt = null, updatedAt = now())
            TriggerType.SCHEDULE -> {
                val cron = input.cron?.trim()?.takeIf { it.isNotEmpty() } ?: throw DomainException.Validation("A schedule is required")
                val zone = zoneOf(input.timezone)
                val schedule = CronSchedule.parse(cron)
                val next = schedule.next(now(), zone) ?: throw DomainException.Validation("This schedule never runs")
                base.copy(
                    type = input.type,
                    enabled = input.enabled,
                    cron = schedule.expression,
                    timezone = zone.id,
                    deviceId = input.deviceId,
                    nextRunAt = if (input.enabled) next else null,
                    updatedAt = now(),
                )
            }
        }
    }

    companion object {
        const val MAX_PER_FLOW = 20

        fun zoneOf(id: String?): ZoneId {
            val v = id?.trim()?.takeIf { it.isNotEmpty() } ?: throw DomainException.Validation("A time zone is required")
            return runCatching { ZoneId.of(v) }.getOrElse { throw DomainException.Validation("Unknown time zone '$v'") }
        }
    }
}

/**
 * Run requests: "run now" from the dashboard, schedules and app-open runs, plus their live logs.
 * Phones fetch requests by long-polling [commands]; the dashboard follows a run with [events].
 */
class RunRequestService(
    private val requests: RunRequestRepository,
    private val devices: DeviceRepository,
    private val flows: FlowRepository,
    private val now: () -> Instant = Instant::now,
    private val pollIntervalMillis: Long = 1_000,
    private val ttl: Duration = DEFAULT_TTL,
) {
    suspend fun runNow(userId: UUID, flowId: UUID, deviceId: UUID?): RunRequest {
        val flow = flows.find(userId, flowId) ?: throw DomainException.NotFound("Flow not found")
        val device = if (deviceId != null) {
            devices.find(userId, deviceId) ?: throw DomainException.Validation("Unknown device")
        } else {
            devices.mostRecent(userId) ?: throw DomainException.Validation("No phone is signed in to this account yet")
        }
        if (!device.remoteRuns) throw DomainException.Validation("Remote runs are turned off on ${device.name}")
        val t = now()
        val request = RunRequest(
            UUID.randomUUID(), userId, flowId, flow.flow.name, flow.flow.appPackage, device.id, null, RunSource.MANUAL,
            RunRequestStatus.PENDING, t, t, t.plus(ttl),
        )
        return requests.insert(request, "Requested from the dashboard for ${device.name}")
    }

    /** The phone started a flow by itself because its app opened; recorded so the dashboard can follow it. */
    suspend fun reportAppOpen(userId: UUID, deviceId: UUID, flowId: UUID, triggerId: UUID?): RunRequest {
        val flow = flows.find(userId, flowId) ?: throw DomainException.NotFound("Flow not found")
        devices.find(userId, deviceId) ?: throw DomainException.NotFound("Device not found")
        val t = now()
        val request = RunRequest(
            UUID.randomUUID(), userId, flowId, flow.flow.name, flow.flow.appPackage, deviceId, triggerId, RunSource.APP_OPEN,
            RunRequestStatus.RUNNING, t, t, t.plus(ttl),
        )
        return requests.insert(request, "Started because ${flow.flow.appPackage} opened")
    }

    suspend fun list(userId: UUID, flowId: UUID?, limit: Int): List<RunRequest> = requests.list(userId, flowId, limit.coerceIn(1, 200))

    suspend fun get(userId: UUID, id: UUID): RunRequest = requests.find(userId, id) ?: throw DomainException.NotFound("Run not found")

    /** Long-poll for a phone: returns as soon as there is something to run or cancel, or after [waitSeconds]. */
    suspend fun commands(userId: UUID, deviceId: UUID, waitSeconds: Int): DeviceCommands {
        val wait = waitSeconds.coerceIn(0, MAX_WAIT_SECONDS) * 1_000L
        var waited = 0L
        while (true) {
            if (!devices.touch(userId, deviceId, now())) throw DomainException.NotFound("Device not found")
            val commands = requests.takeForDevice(userId, deviceId, now())
            if (commands.run.isNotEmpty() || commands.cancel.isNotEmpty() || waited >= wait) return commands
            delay(pollIntervalMillis)
            waited += pollIntervalMillis
        }
    }

    /** Status and log lines from the phone running the request. */
    suspend fun report(userId: UUID, id: UUID, deviceId: UUID, status: RunRequestStatus?, events: List<Pair<String, String>>): RunRequest {
        val request = get(userId, id)
        if (request.deviceId != deviceId) throw DomainException.Forbidden("This run belongs to another device")
        if (events.size > MAX_EVENTS_PER_REPORT) throw DomainException.Validation("At most $MAX_EVENTS_PER_REPORT events per report")
        val clean = events.map { (kind, message) -> kind.trim().take(20).ifEmpty { "info" } to message.trim().take(MAX_MESSAGE) }
        var current = request
        if (status != null && status != request.status) {
            if (request.status.isFinal) throw DomainException.Conflict("This run already finished")
            if (!allowedFromDevice(request.status, status)) throw DomainException.Validation("Can't change a ${request.status} run to $status")
            current = requests.setStatus(userId, id, status, now()) ?: current
        }
        if (clean.isNotEmpty()) requests.addEvents(id, clean, now())
        return current
    }

    suspend fun cancel(userId: UUID, id: UUID): RunRequest {
        val request = get(userId, id)
        val next = when (request.status) {
            RunRequestStatus.PENDING -> RunRequestStatus.CANCELLED
            RunRequestStatus.DELIVERED, RunRequestStatus.RUNNING -> RunRequestStatus.CANCEL_REQUESTED
            RunRequestStatus.CANCEL_REQUESTED -> return request
            else -> throw DomainException.Conflict("This run already finished")
        }
        val updated = requests.setStatus(userId, id, next, now()) ?: request
        requests.addEvents(id, listOf("status" to if (next == RunRequestStatus.CANCELLED) "Cancelled before the phone picked it up" else "Stop requested from the dashboard"), now())
        return updated
    }

    /** Long-poll for the dashboard's live log: new events after [afterId], or the current state after [waitSeconds]. */
    suspend fun events(userId: UUID, id: UUID, afterId: Long, waitSeconds: Int): Pair<RunRequest, List<RunEvent>> {
        val wait = waitSeconds.coerceIn(0, MAX_WAIT_SECONDS) * 1_000L
        var waited = 0L
        while (true) {
            val request = get(userId, id)
            val events = requests.events(userId, id, afterId, MAX_EVENTS_PAGE)
            if (events.isNotEmpty() || request.status.isFinal || waited >= wait) return request to events
            delay(pollIntervalMillis)
            waited += pollIntervalMillis
        }
    }

    private fun allowedFromDevice(from: RunRequestStatus, to: RunRequestStatus): Boolean = when (from) {
        RunRequestStatus.PENDING, RunRequestStatus.DELIVERED -> to == RunRequestStatus.RUNNING || to.isFinal
        RunRequestStatus.RUNNING -> to.isFinal
        RunRequestStatus.CANCEL_REQUESTED -> to.isFinal
        else -> false
    }

    companion object {
        val DEFAULT_TTL: Duration = Duration.ofMinutes(10)
        const val MAX_WAIT_SECONDS = 30
        const val MAX_EVENTS_PER_REPORT = 50
        const val MAX_EVENTS_PAGE = 200
        const val MAX_MESSAGE = 300
    }
}

/**
 * Fires due schedule triggers. Safe with many backend replicas: each tick claims due rows with
 * `FOR UPDATE SKIP LOCKED`, so a trigger fires once per due time.
 */
class TriggerScheduler(
    private val triggers: TriggerRepository,
    private val requests: RunRequestRepository,
    private val now: () -> Instant = Instant::now,
    private val ttl: Duration = RunRequestService.DEFAULT_TTL,
) {
    /** One pass: expire stale requests, then fire due triggers. Returns the number of triggers fired. */
    suspend fun tick(at: Instant = now()): Int {
        val t = at
        requests.expire(t)
        return triggers.claimDue(t, BATCH) { due ->
            val trigger = due.trigger
            val next = runCatching { CronSchedule.parse(trigger.cron!!).next(t, ZoneId.of(trigger.timezone!!)) }.getOrNull()
            val deviceId = due.deviceId
            if (deviceId == null) {
                DueOutcome(null, "No phone available", next)
            } else {
                val request = RunRequest(
                    UUID.randomUUID(), trigger.userId, trigger.flowId, due.flowName, due.appPackage, deviceId, trigger.id,
                    RunSource.SCHEDULE, RunRequestStatus.PENDING, t, t, t.plus(ttl),
                )
                DueOutcome(request, "Scheduled run (${trigger.cron}, ${trigger.timezone})", next)
            }
        }
    }

    companion object {
        const val BATCH = 50
    }
}
