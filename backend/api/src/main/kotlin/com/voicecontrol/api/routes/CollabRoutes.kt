package com.voicecontrol.api.routes

import com.voicecontrol.api.plugins.API_KEY_AUTH
import com.voicecontrol.api.plugins.JWT_AUTH
import com.voicecontrol.api.plugins.keyContext
import com.voicecontrol.api.plugins.orgContext
import com.voicecontrol.api.plugins.requireScope
import com.voicecontrol.api.plugins.requireUser
import com.voicecontrol.api.plugins.userId
import com.voicecontrol.application.collab.AnalyticsService
import com.voicecontrol.application.collab.CommentService
import com.voicecontrol.application.collab.LayoutService
import com.voicecontrol.application.collab.PresenceService
import com.voicecontrol.domain.collab.DailyRuns
import com.voicecontrol.domain.collab.FlowAnalytics
import com.voicecontrol.domain.collab.FlowComment
import com.voicecontrol.domain.collab.FlowUsage
import com.voicecontrol.domain.collab.FlowUsageRow
import com.voicecontrol.domain.collab.NodePosition
import com.voicecontrol.domain.common.DomainException
import com.voicecontrol.domain.org.ApiScope
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable data class UsageDto(
    val runs: Int,
    val completed: Int,
    val stopped: Int,
    val failed: Int,
    val users: Int,
    /** completed / runs, or null without runs. */
    val successRate: Double? = null,
    val avgDurationMillis: Long? = null,
    val lastRunAt: String? = null,
) {
    companion object {
        fun from(u: FlowUsage) = UsageDto(
            u.runs, u.completed, u.stopped, u.failed, u.users, if (u.runs == 0) null else u.completed.toDouble() / u.runs, u.avgDurationMillis, u.lastRunAt?.toString(),
        )
    }
}
@Serializable data class DailyDto(val date: String, val completed: Int, val stopped: Int, val failed: Int) {
    companion object {
        fun from(d: DailyRuns) = DailyDto(
            d.date.toString(),
            d.counts[com.voicecontrol.domain.history.RunStatus.COMPLETED] ?: 0,
            d.counts[com.voicecontrol.domain.history.RunStatus.STOPPED] ?: 0,
            d.counts[com.voicecontrol.domain.history.RunStatus.FAILED] ?: 0,
        )
    }
}
@Serializable data class StepStatsDto(val elementId: String, val label: String, val total: Int, val outcomes: Map<String, Int>)
@Serializable data class FlowAnalyticsDto(
    val usage: UsageDto,
    val daily: List<DailyDto>,
    val steps: List<StepStatsDto>,
    val interpretedBy: Map<String, Int>,
    val remoteRuns: Map<String, Int>,
    /** The same number of days just before the period, for showing changes. */
    val previous: UsageDto? = null,
) {
    companion object {
        fun from(a: FlowAnalytics, previous: FlowUsage? = null) = FlowAnalyticsDto(
            UsageDto.from(a.usage),
            a.daily.map(DailyDto::from),
            a.steps.map { s -> StepStatsDto(s.elementId, s.label, s.total, s.outcomes.mapKeys { it.key.name }) },
            a.interpretedBy,
            a.remoteRuns,
            previous?.let(UsageDto::from),
        )
    }
}
@Serializable data class OverviewRowDto(val flowId: String, val name: String, val appPackage: String, val usage: UsageDto) {
    companion object {
        fun from(r: FlowUsageRow) = OverviewRowDto(r.flowId.toString(), r.name, r.appPackage, UsageDto.from(r.usage))
    }
}
@Serializable data class OverviewDto(
    val flows: List<OverviewRowDto>,
    val daily: List<DailyDto>,
    val totals: UsageDto,
    /** Totals for the same number of days just before the period. */
    val previous: UsageDto? = null,
)

@Serializable data class CommentDto(
    val id: String,
    val flowId: String,
    val userId: String? = null,
    val authorName: String? = null,
    val authorEmail: String? = null,
    val stepId: String? = null,
    val body: String,
    val createdAt: String,
    val editedAt: String? = null,
    val resolvedAt: String? = null,
) {
    companion object {
        fun from(c: FlowComment) = CommentDto(
            c.id.toString(), c.flowId.toString(), c.userId?.toString(), c.authorName, c.authorEmail, c.stepId, c.body,
            c.createdAt.toString(), c.editedAt?.toString(), c.resolvedAt?.toString(),
        )
    }
}
@Serializable data class NewCommentRequest(val body: String, val stepId: String? = null)
@Serializable data class EditCommentRequest(val body: String? = null, val resolved: Boolean? = null)
@Serializable data class PresenceRequest(val editing: Boolean = false)
@Serializable data class PresenceUserDto(val userId: String, val name: String, val editing: Boolean, val seenAt: String)
@Serializable data class PresenceDto(val others: List<PresenceUserDto>, val currentVersion: Int)
@Serializable data class PositionDto(val x: Double, val y: Double)
@Serializable data class LayoutDto(val positions: Map<String, PositionDto>)

private fun ApplicationCall.uuidParam(name: String): UUID =
    runCatching { UUID.fromString(parameters[name]) }.getOrElse { throw DomainException.NotFound("Not found") }

private fun ApplicationCall.days(): Int = request.queryParameters["days"]?.toIntOrNull()?.coerceIn(1, 365) ?: 30

fun Route.collabRoutes(analytics: AnalyticsService, comments: CommentService, presence: PresenceService, layouts: LayoutService) {
    authenticate(JWT_AUTH, API_KEY_AUTH) {
        get("/v1/flows/{id}/analytics") {
            call.requireScope(ApiScope.FLOWS_READ)
            val id = call.uuidParam("id")
            val tz = call.request.queryParameters["tz"]
            val result = analytics.flow(call.userId, id, call.days(), tz, call.keyContext)
            val compared = analytics.comparison(call.userId, null, id, call.days(), tz, call.keyContext)
            call.respond(FlowAnalyticsDto.from(result, compared.previous))
        }
        get("/v1/analytics/flows") {
            call.requireScope(ApiScope.FLOWS_READ)
            val tz = call.request.queryParameters["tz"]
            val (rows, daily) = analytics.overview(call.userId, call.orgContext, call.days(), tz)
            // Totals count each run and each person once, even across several flows.
            val compared = analytics.comparison(call.userId, call.orgContext, null, call.days(), tz)
            call.respond(OverviewDto(rows.map(OverviewRowDto::from), daily.map(DailyDto::from), UsageDto.from(compared.current), UsageDto.from(compared.previous)))
        }
    }

    authenticate(JWT_AUTH) {
        route("/v1/flows/{id}") {
            get("/comments") { call.respond(comments.list(call.userId, call.uuidParam("id")).map(CommentDto::from)) }
            post("/comments") {
                val body = call.receive<NewCommentRequest>()
                call.respond(HttpStatusCode.Created, CommentDto.from(comments.create(call.userId, call.uuidParam("id"), body.stepId?.takeIf { it.isNotBlank() }, body.body)))
            }
            post("/presence") {
                call.requireUser()
                val body = call.receive<PresenceRequest>()
                val (others, version) = presence.heartbeat(call.userId, call.uuidParam("id"), body.editing)
                call.respond(PresenceDto(others.map { PresenceUserDto(it.userId.toString(), it.name, it.editing, it.seenAt.toString()) }, version))
            }
            delete("/presence") {
                presence.leave(call.userId, call.uuidParam("id"))
                call.respond(HttpStatusCode.NoContent)
            }
            get("/layout") {
                call.respond(LayoutDto(layouts.get(call.userId, call.uuidParam("id")).mapValues { PositionDto(it.value.x, it.value.y) }))
            }
            put("/layout") {
                val body = call.receive<LayoutDto>()
                layouts.save(call.userId, call.uuidParam("id"), body.positions.mapValues { NodePosition(it.value.x, it.value.y) })
                call.respond(HttpStatusCode.NoContent)
            }
        }
        route("/v1/comments/{commentId}") {
            patch {
                val body = call.receive<EditCommentRequest>()
                val id = call.uuidParam("commentId")
                var result = body.body?.let { comments.edit(call.userId, id, it) }
                body.resolved?.let { result = comments.resolve(call.userId, id, it) }
                call.respond(CommentDto.from(result ?: throw DomainException.Validation("Nothing to change")))
            }
            delete {
                comments.delete(call.userId, call.uuidParam("commentId"))
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
