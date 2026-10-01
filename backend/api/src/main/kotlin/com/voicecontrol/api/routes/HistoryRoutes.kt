package com.voicecontrol.api.routes

import com.voicecontrol.api.plugins.JWT_AUTH
import com.voicecontrol.api.plugins.userId
import com.voicecontrol.application.history.HistoryService
import com.voicecontrol.domain.ai.Language
import com.voicecontrol.domain.common.DomainException
import com.voicecontrol.domain.history.Run
import com.voicecontrol.domain.history.RunStatus
import com.voicecontrol.domain.history.ScreenRecord
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

/** Same shape as the phone's `SessionSummary`. */
@Serializable
data class RunDto(
    val sessionId: String,
    val appPackage: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val status: RunStatus,
    val language: Language,
    val screens: List<ScreenRecord>,
    val filledCount: Int = 0,
    val stepCount: Int = 0,
) {
    fun toDomain(userId: UUID) = Run(
        id = runCatching { UUID.fromString(sessionId) }.getOrElse { throw DomainException.Validation("sessionId must be a UUID") },
        userId = userId,
        appPackage = appPackage,
        startedAt = Instant.ofEpochMilli(startedAtMillis),
        endedAt = Instant.ofEpochMilli(endedAtMillis),
        status = status,
        language = language,
        screens = screens,
    )

    companion object {
        fun from(r: Run) = RunDto(
            r.id.toString(), r.appPackage, r.startedAt.toEpochMilli(), r.endedAt.toEpochMilli(), r.status, r.language, r.screens, r.filledCount, r.stepCount,
        )
    }
}

@Serializable data class UploadRunsRequest(val runs: List<RunDto>)
@Serializable data class UploadRunsResponse(val inserted: Int)
@Serializable data class AppCount(val appPackage: String, val runs: Int)
@Serializable data class RunStatsDto(val totalRuns: Int, val completedRuns: Int, val fieldsFilled: Int, val topApps: List<AppCount>)

fun Route.historyRoutes(history: HistoryService) {
    authenticate(JWT_AUTH) {
        route("/v1/runs") {
            post {
                val body = call.receive<UploadRunsRequest>()
                val userId = call.userId
                call.respond(UploadRunsResponse(history.upload(userId, body.runs.map { it.toDomain(userId) })))
            }
            get {
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
                val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
                val items = history.list(call.userId, call.request.queryParameters["appPackage"], limit, offset).map(RunDto::from)
                call.respond(Page(items, limit, offset))
            }
            delete {
                history.clear(call.userId)
                call.respond(HttpStatusCode.NoContent)
            }
            get("/stats") {
                val s = history.stats(call.userId)
                call.respond(RunStatsDto(s.totalRuns, s.completedRuns, s.fieldsFilled, s.topApps.map { AppCount(it.first, it.second) }))
            }
            get("/{id}") { call.respond(RunDto.from(history.get(call.userId, call.runId()))) }
            delete("/{id}") {
                history.delete(call.userId, call.runId())
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

private fun ApplicationCall.runId(): UUID =
    parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: throw DomainException.NotFound("Run not found")
