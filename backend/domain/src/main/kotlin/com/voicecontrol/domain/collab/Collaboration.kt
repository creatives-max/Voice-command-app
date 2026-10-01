package com.voicecontrol.domain.collab

import com.voicecontrol.domain.history.RunStatus
import com.voicecontrol.domain.history.StepOutcome
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// Analytics ---------------------------------------------------------------------------------------

/** Runs of one day, by how they ended. */
data class DailyRuns(val date: LocalDate, val counts: Map<RunStatus, Int>) {
    val total: Int get() = counts.values.sum()
}

/** What happened to one step of a flow across runs. */
data class StepStats(val elementId: String, val label: String, val outcomes: Map<StepOutcome, Int>) {
    val total: Int get() = outcomes.values.sum()
}

data class FlowUsage(
    val runs: Int,
    val completed: Int,
    val stopped: Int,
    val failed: Int,
    val users: Int,
    val avgDurationMillis: Long?,
    val lastRunAt: Instant?,
) {
    companion object {
        val EMPTY = FlowUsage(0, 0, 0, 0, 0, null, null)
    }
}

data class FlowAnalytics(
    val usage: FlowUsage,
    val daily: List<DailyRuns>,
    val steps: List<StepStats>,
    /** How answers were understood: "rules", "llm", "cache"… with counts. */
    val interpretedBy: Map<String, Int>,
    /** Remote runs (dashboard, schedules, app open) by final status. */
    val remoteRuns: Map<String, Int>,
)

/** One row of the overview table: a flow and its usage in the period. */
data class FlowUsageRow(val flowId: UUID, val name: String, val appPackage: String, val usage: FlowUsage)

interface AnalyticsRepository {
    /** Runs whose screens used [flowId] since [since]; [zone] decides day boundaries. */
    suspend fun flowAnalytics(flowId: UUID, since: Instant, zone: String): FlowAnalytics
    /** Usage of each of [flowIds] since [since] plus all their runs per day. */
    suspend fun overview(flowIds: List<UUID>, since: Instant, zone: String): Pair<Map<UUID, FlowUsage>, List<DailyRuns>>
    /** Usage of all of [flowIds] together (each run counted once) for runs started in [from, until). */
    suspend fun totals(flowIds: List<UUID>, from: Instant, until: Instant): FlowUsage
}

// Comments ----------------------------------------------------------------------------------------

data class FlowComment(
    val id: UUID,
    val flowId: UUID,
    val userId: UUID?,
    val authorName: String?,
    val authorEmail: String?,
    val stepId: String?,
    val body: String,
    val createdAt: Instant,
    val editedAt: Instant?,
    val resolvedAt: Instant?,
)

interface CommentRepository {
    suspend fun create(comment: FlowComment): FlowComment
    suspend fun list(flowId: UUID): List<FlowComment>
    suspend fun find(id: UUID): FlowComment?
    suspend fun edit(id: UUID, body: String, at: Instant): FlowComment?
    suspend fun resolve(id: UUID, resolvedBy: UUID?, at: Instant?): FlowComment?
    suspend fun delete(id: UUID): Boolean
}

// Presence ----------------------------------------------------------------------------------------

/** Someone who has the flow open on the dashboard right now. */
data class Presence(val userId: UUID, val name: String, val editing: Boolean, val seenAt: Instant)

interface PresenceStore {
    /** Records a heartbeat and returns everyone seen within the presence window. */
    suspend fun heartbeat(flowId: UUID, presence: Presence): List<Presence>
    suspend fun leave(flowId: UUID, userId: UUID)
}

// Canvas layout -----------------------------------------------------------------------------------

data class NodePosition(val x: Double, val y: Double)

interface LayoutRepository {
    suspend fun get(flowId: UUID): Map<String, NodePosition>?
    suspend fun save(flowId: UUID, positions: Map<String, NodePosition>, userId: UUID, at: Instant)
}
