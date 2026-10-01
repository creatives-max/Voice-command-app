package com.voicecontrol.api.routes

import com.voicecontrol.api.plugins.JWT_AUTH
import com.voicecontrol.api.plugins.userId
import com.voicecontrol.application.account.AccountService
import com.voicecontrol.application.account.CrashInput
import com.voicecontrol.application.account.CrashService
import com.voicecontrol.domain.account.CrashGroup
import com.voicecontrol.domain.common.DomainException
import com.voicecontrol.domain.event.RateLimiter
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.util.UUID

@Serializable data class DeleteAccountRequest(val password: String)
@Serializable data class CrashReportDto(
    val exception: String,
    val message: String? = null,
    val stacktrace: String,
    val thread: String? = null,
    val appVersion: String? = null,
    val androidSdk: Int? = null,
    val deviceModel: String? = null,
    val occurredAtMillis: Long,
    val installId: String? = null,
)
@Serializable data class CrashUploadRequest(val reports: List<CrashReportDto>)
@Serializable data class CrashUploadResponse(val accepted: Int)
@Serializable data class CrashGroupDto(
    val fingerprint: String,
    val exception: String,
    val message: String? = null,
    val topFrame: String? = null,
    val count: Int,
    val firstSeen: String,
    val lastSeen: String,
    val appVersions: List<String>,
    val latestStacktrace: String,
) {
    companion object {
        fun from(g: CrashGroup) = CrashGroupDto(
            g.fingerprint, g.exception, g.message, g.topFrame, g.count, g.firstSeen.toString(), g.lastSeen.toString(), g.appVersions, g.latestStacktrace,
        )
    }
}

fun Route.accountRoutes(account: AccountService, crashes: CrashService, limiter: RateLimiter) {
    authenticate(JWT_AUTH) {
        // GDPR: everything stored about you, as JSON.
        get("/v1/me/export") {
            val doc = account.export(call.userId)
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "voicecontrol-data-${LocalDate.now()}.json").toString(),
            )
            call.respondText(doc, ContentType.Application.Json)
        }
        // GDPR: erase the account (password required again).
        delete("/v1/me") {
            account.delete(call.userId, call.receive<DeleteAccountRequest>().password)
            call.respond(HttpStatusCode.NoContent)
        }
        get("/v1/crashes") { call.respond(crashes.groups(call.userId).map(CrashGroupDto::from)) }
    }
    // Crash uploads work signed in or not (a crash may happen before sign-in); limited per client IP.
    authenticate(JWT_AUTH, optional = true) {
        post("/v1/crashes") {
            if (!limiter.tryAcquire("crash:${call.request.origin.remoteHost}", 30, 60)) throw DomainException.RateLimited()
            val userId = call.principal<JWTPrincipal>()?.payload?.subject?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            val body = call.receive<CrashUploadRequest>()
            val accepted = crashes.report(
                userId,
                body.reports.map { CrashInput(it.exception, it.message, it.stacktrace, it.thread, it.appVersion, it.androidSdk, it.deviceModel, it.occurredAtMillis, it.installId) },
            )
            call.respond(CrashUploadResponse(accepted))
        }
    }
}
