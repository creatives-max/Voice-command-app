package com.voicecontrol.domain.automation

import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

/** A phone signed in to the account. It long-polls for run requests while its accessibility service runs. */
data class Device(
    val id: UUID,
    val userId: UUID,
    val name: String,
    val platform: String,
    val appVersion: String?,
    /** The user allows the dashboard and schedules to start flows on this phone. */
    val remoteRuns: Boolean,
    val lastSeenAt: Instant,
    val createdAt: Instant,
)

@Serializable
/** VOICE: saying [FlowTrigger.phrase] on the phone runs the flow (a voice macro). */
enum class TriggerType { APP_OPEN, SCHEDULE, VOICE }

data class FlowTrigger(
    val id: UUID,
    val userId: UUID,
    val flowId: UUID,
    val type: TriggerType,
    val enabled: Boolean,
    /** 5-field cron (minute hour day-of-month month day-of-week), SCHEDULE only. */
    val cron: String?,
    /** IANA time zone the cron is evaluated in, SCHEDULE only. */
    val timezone: String?,
    /** Phone to run on; null = the most recently seen phone. */
    val deviceId: UUID?,
    val nextRunAt: Instant?,
    val lastRunAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** What the user says to run the flow, VOICE only. */
    val phrase: String? = null,
)

@Serializable
enum class RunSource { MANUAL, SCHEDULE, APP_OPEN, VOICE }

@Serializable
enum class RunRequestStatus {
    PENDING, DELIVERED, RUNNING, COMPLETED, FAILED, STOPPED, CANCEL_REQUESTED, CANCELLED, EXPIRED;

    val isFinal: Boolean get() = this in setOf(COMPLETED, FAILED, STOPPED, CANCELLED, EXPIRED)
}

data class RunRequest(
    val id: UUID,
    val userId: UUID,
    val flowId: UUID?,
    val flowName: String,
    val appPackage: String,
    val deviceId: UUID?,
    val triggerId: UUID?,
    val source: RunSource,
    val status: RunRequestStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
    val expiresAt: Instant,
)

/** One line of a run's live log. Never contains spoken or typed values. */
data class RunEvent(val id: Long, val requestId: UUID, val at: Instant, val kind: String, val message: String)

interface DeviceRepository {
    suspend fun upsert(device: Device): Device
    suspend fun find(userId: UUID, id: UUID): Device?
    suspend fun list(userId: UUID): List<Device>
    suspend fun update(userId: UUID, id: UUID, name: String?, remoteRuns: Boolean?): Device?
    suspend fun touch(userId: UUID, id: UUID, at: Instant): Boolean
    suspend fun delete(userId: UUID, id: UUID): Boolean
    /** Most recently seen phone that accepts remote runs. */
    suspend fun mostRecent(userId: UUID): Device?
}

interface TriggerRepository {
    suspend fun insert(trigger: FlowTrigger): FlowTrigger
    suspend fun update(trigger: FlowTrigger): FlowTrigger?
    suspend fun find(userId: UUID, id: UUID): FlowTrigger?
    suspend fun listForFlow(userId: UUID, flowId: UUID): List<FlowTrigger>
    suspend fun listForUser(userId: UUID): List<FlowTrigger>
    suspend fun delete(userId: UUID, id: UUID): Boolean

    /**
     * Atomically claims due schedule triggers (multi-replica safe: rows are locked with SKIP LOCKED),
     * lets [fire] create the run request and compute the next time, and stores it.
     */
    suspend fun claimDue(now: Instant, limit: Int, fire: (DueTrigger) -> DueOutcome): Int
}

/** A claimed schedule trigger with what is needed to create its run request. */
data class DueTrigger(val trigger: FlowTrigger, val flowName: String, val appPackage: String, val deviceId: UUID?)

/** What firing a due trigger produced: the request to insert (if any) and the next run time. */
data class DueOutcome(val request: RunRequest?, val event: String, val nextRunAt: Instant?)

interface RunRequestRepository {
    suspend fun insert(request: RunRequest, firstEvent: String): RunRequest
    suspend fun find(userId: UUID, id: UUID): RunRequest?
    suspend fun list(userId: UUID, flowId: UUID?, limit: Int): List<RunRequest>
    /** Marks this device's PENDING requests DELIVERED and returns them, plus requests to cancel. */
    suspend fun takeForDevice(userId: UUID, deviceId: UUID, now: Instant): DeviceCommands
    suspend fun setStatus(userId: UUID, id: UUID, status: RunRequestStatus, now: Instant): RunRequest?
    suspend fun addEvents(requestId: UUID, events: List<Pair<String, String>>, now: Instant)
    suspend fun events(userId: UUID, id: UUID, afterId: Long, limit: Int): List<RunEvent>
    /** Marks undelivered requests past their expiry EXPIRED. Returns how many. */
    suspend fun expire(now: Instant): Int
}

data class DeviceCommands(val run: List<RunRequest>, val cancel: List<RunRequest>)
