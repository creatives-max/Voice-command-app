package com.voicecontrol.api.plugins

import com.voicecontrol.domain.common.DomainException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.event.Level
import java.util.UUID

@Serializable
data class ApiError(val error: String, val message: String)

val ApiJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

fun Application.configureHttp(corsOrigins: List<String>) {
    install(ContentNegotiation) { json(ApiJson) }
    install(CallId) {
        retrieveFromHeader(HttpHeaders.XRequestId)
        generate { UUID.randomUUID().toString() }
        replyToHeader(HttpHeaders.XRequestId)
    }
    install(CallLogging) {
        level = Level.INFO
        callIdMdc("requestId")
        filter { it.request.path() != "/health" }
    }
    install(CORS) {
        corsOrigins.forEach { origin ->
            val uri = runCatching { java.net.URI(origin) }.getOrNull() ?: return@forEach
            allowHost(uri.authority, schemes = listOf(uri.scheme))
        }
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(ORG_HEADER)
        allowHeader("X-Api-Key")
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Delete)
        allowCredentials = true
    }
    install(StatusPages) {
        exception<DomainException> { call, e ->
            val (status, code) = when (e) {
                is DomainException.Validation -> HttpStatusCode.BadRequest to "validation_error"
                is DomainException.NotFound -> HttpStatusCode.NotFound to "not_found"
                is DomainException.Conflict -> HttpStatusCode.Conflict to "conflict"
                is DomainException.Unauthorized -> HttpStatusCode.Unauthorized to "unauthorized"
                is DomainException.Forbidden -> HttpStatusCode.Forbidden to "forbidden"
                is DomainException.RateLimited -> HttpStatusCode.TooManyRequests to "rate_limited"
                is DomainException.Upstream -> HttpStatusCode.BadGateway to "upstream_error"
            }
            call.respond(status, ApiError(code, e.message ?: code))
        }
        exception<BadRequestException> { call, e ->
            call.respond(HttpStatusCode.BadRequest, ApiError("bad_request", e.cause?.message ?: e.message ?: "Malformed request"))
        }
        exception<Throwable> { call, e ->
            call.application.environment.log.error("Unhandled error", e)
            call.respond(HttpStatusCode.InternalServerError, ApiError("internal_error", "Something went wrong"))
        }
    }
}
