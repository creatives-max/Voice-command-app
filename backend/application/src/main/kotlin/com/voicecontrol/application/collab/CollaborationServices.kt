package com.voicecontrol.application.collab

import com.voicecontrol.application.flow.FlowService
import com.voicecontrol.application.flow.KeyContext
import com.voicecontrol.domain.collab.AnalyticsRepository
import com.voicecontrol.domain.collab.CommentRepository
import com.voicecontrol.domain.collab.DailyRuns
import com.voicecontrol.domain.collab.FlowAnalytics
import com.voicecontrol.domain.collab.FlowComment
import com.voicecontrol.domain.collab.FlowUsageRow
import com.voicecontrol.domain.collab.LayoutRepository
import com.voicecontrol.domain.collab.NodePosition
import com.voicecontrol.domain.collab.Presence
import com.voicecontrol.domain.collab.PresenceStore
import com.voicecontrol.domain.common.DomainException
import com.voicecontrol.domain.org.Actor
import com.voicecontrol.domain.org.AuditLog
import com.voicecontrol.domain.org.Role
import com.voicecontrol.domain.user.UserRepository
import java.time.Clock
import java.time.Duration
import java.time.ZoneId
import java.util.UUID

/**
 * Usage and success analytics computed from uploaded run history. A flow's analytics include every run
 * that used it, so an organization flow counts all members' runs (only aggregates are returned).
 */
class AnalyticsService(
    private val repo: AnalyticsRepository,
    private val flows: FlowService,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun flow(userId: UUID, flowId: UUID, days: Int, zone: String?, key: KeyContext? = null): FlowAnalytics {
        flows.get(userId, flowId, key)
        return repo.flowAnalytics(flowId, since(days, zone), zoneOf(zone))
    }

    /** Every flow of the workspace (personal or [orgId]) with its usage, busiest first, and daily totals. */
    suspend fun overview(userId: UUID, orgId: UUID?, days: Int, zone: String?): Pair<List<FlowUsageRow>, List<DailyRuns>> {
        val list = flows.list(userId, null, 200, 0, orgId)
        if (list.isEmpty()) return emptyList<FlowUsageRow>() to emptyList()
        val (usage, daily) = repo.overview(list.map { it.id }, since(days, zone), zoneOf(zone))
        val rows = list.mapNotNull { f -> usage[f.id]?.let { FlowUsageRow(f.id, f.name, f.appPackage, it) } }
            .sortedWith(compareByDescending<FlowUsageRow> { it.usage.runs }.thenBy { it.name })
        return rows to daily
    }

    private fun since(days: Int, zone: String?) =
        clock.instant().atZone(ZoneId.of(zoneOf(zone))).toLocalDate().minusDays((days.coerceIn(1, 365) - 1).toLong()).atStartOfDay(ZoneId.of(zoneOf(zone))).toInstant()

    private fun zoneOf(zone: String?): String = zone?.takeIf { z -> runCatching { ZoneId.of(z) }.isSuccess } ?: "UTC"
}

/** Comments on flows. Anyone who can open the flow may comment; authors edit and delete their own; editors resolve. */
class CommentService(
    private val repo: CommentRepository,
    private val flows: FlowService,
    private val users: UserRepository,
    private val audit: AuditLog,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun list(userId: UUID, flowId: UUID): List<FlowComment> {
        flows.get(userId, flowId)
        return repo.list(flowId)
    }

    suspend fun create(userId: UUID, flowId: UUID, stepId: String?, body: String): FlowComment {
        val flow = flows.get(userId, flowId)
        val text = clean(body)
        if (stepId != null && flow.version.steps.none { it.id == stepId }) throw DomainException.Validation("That step is not in the current version")
        val user = users.findById(userId)
        val comment = repo.create(FlowComment(UUID.randomUUID(), flowId, userId, user?.name, user?.email, stepId, text, clock.instant(), null, null))
        flow.flow.orgId?.let { audit.record(it, Actor(userId), "flow.commented", "flow", flowId.toString(), mapOf("name" to flow.flow.name)) }
        return comment
    }

    suspend fun edit(userId: UUID, commentId: UUID, body: String): FlowComment {
        val comment = own(userId, commentId)
        return repo.edit(comment.id, clean(body), clock.instant()) ?: throw DomainException.NotFound("Comment not found")
    }

    suspend fun resolve(userId: UUID, commentId: UUID, resolved: Boolean): FlowComment {
        val comment = accessible(userId, commentId)
        val flow = flows.get(userId, comment.flowId)
        if (comment.userId != userId && !flows.roleFor(userId, flow.flow).atLeast(Role.EDITOR)) {
            throw DomainException.Forbidden("Only editors can resolve other people's comments")
        }
        return repo.resolve(commentId, if (resolved) userId else null, if (resolved) clock.instant() else null) ?: throw DomainException.NotFound("Comment not found")
    }

    /** Authors delete their comments; organization admins can delete any. */
    suspend fun delete(userId: UUID, commentId: UUID) {
        val comment = accessible(userId, commentId)
        if (comment.userId != userId) {
            val flow = flows.get(userId, comment.flowId)
            if (flows.roleFor(userId, flow.flow) != Role.ADMIN) throw DomainException.Forbidden("You can only delete your own comments")
        }
        repo.delete(commentId)
    }

    private suspend fun accessible(userId: UUID, commentId: UUID): FlowComment {
        val comment = repo.find(commentId) ?: throw DomainException.NotFound("Comment not found")
        runCatching { flows.get(userId, comment.flowId) }.getOrElse { throw DomainException.NotFound("Comment not found") }
        return comment
    }

    private suspend fun own(userId: UUID, commentId: UUID): FlowComment {
        val comment = accessible(userId, commentId)
        if (comment.userId != userId) throw DomainException.Forbidden("You can only edit your own comments")
        return comment
    }

    private fun clean(body: String): String {
        val text = body.trim()
        if (text.isEmpty() || text.length > MAX_BODY) throw DomainException.Validation("Comments must be 1-$MAX_BODY characters")
        return text
    }

    companion object {
        const val MAX_BODY = 2_000
    }
}

/** Who else has a flow open in the dashboard, refreshed by heartbeats. */
class PresenceService(
    private val store: PresenceStore,
    private val flows: FlowService,
    private val users: UserRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** Records the caller and returns the others, plus the flow's current version (to notice concurrent saves). */
    suspend fun heartbeat(userId: UUID, flowId: UUID, editing: Boolean): Pair<List<Presence>, Int> {
        val flow = flows.get(userId, flowId)
        val user = users.findById(userId)
        val name = user?.name?.takeIf { it.isNotBlank() } ?: user?.email ?: "Someone"
        val all = store.heartbeat(flowId, Presence(userId, name, editing, clock.instant()))
        return all.filter { it.userId != userId } to flow.flow.currentVersion
    }

    suspend fun leave(userId: UUID, flowId: UUID) = store.leave(flowId, userId)

    companion object {
        val WINDOW: Duration = Duration.ofSeconds(45)
    }
}

/** Positions of the visual builder's nodes, shared by everyone editing the flow. */
class LayoutService(private val repo: LayoutRepository, private val flows: FlowService, private val clock: Clock = Clock.systemUTC()) {
    suspend fun get(userId: UUID, flowId: UUID): Map<String, NodePosition> {
        flows.get(userId, flowId)
        return repo.get(flowId).orEmpty()
    }

    suspend fun save(userId: UUID, flowId: UUID, positions: Map<String, NodePosition>) {
        val flow = flows.get(userId, flowId)
        if (!flows.roleFor(userId, flow.flow).atLeast(Role.EDITOR)) throw DomainException.Forbidden("Viewers can't change this organization's flows")
        if (positions.size > MAX_NODES) throw DomainException.Validation("Too many nodes")
        if (positions.keys.any { it.length > 100 } || positions.values.any { !it.x.isFinite() || !it.y.isFinite() || kotlin.math.abs(it.x) > 1e6 || kotlin.math.abs(it.y) > 1e6 }) {
            throw DomainException.Validation("Invalid node positions")
        }
        repo.save(flowId, positions, userId, clock.instant())
    }

    companion object {
        const val MAX_NODES = 200
    }
}
