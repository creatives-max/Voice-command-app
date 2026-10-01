package com.voicecontrol.api.plugins

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.voicecontrol.domain.common.DomainException
import com.voicecontrol.infrastructure.config.AppConfig
import com.voicecontrol.application.flow.KeyContext
import com.voicecontrol.domain.org.ApiKey
import com.voicecontrol.domain.org.ApiScope
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.AuthenticationContext
import io.ktor.server.auth.AuthenticationFailedCause
import io.ktor.server.auth.AuthenticationProvider
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import java.util.UUID

const val JWT_AUTH = "jwt"
const val API_KEY_AUTH = "api-key"

/** A third party calling with an organization API key; it acts as the key's creator within the key's organization. */
class ApiKeyPrincipal(val key: ApiKey, val actingUserId: UUID)

/** Reads `X-Api-Key: vck_…` or `Authorization: Bearer vck_…`. */
class ApiKeyAuthenticationProvider(config: Config) : AuthenticationProvider(config) {
    private val resolve = config.resolve

    class Config(name: String?) : AuthenticationProvider.Config(name) {
        var resolve: suspend (String) -> ApiKeyPrincipal? = { null }
    }

    override suspend fun onAuthenticate(context: AuthenticationContext) {
        val headers = context.call.request.headers
        val secret = headers["X-Api-Key"]?.trim()
            ?: headers[HttpHeaders.Authorization]?.trim()?.takeIf { it.startsWith("Bearer vck_") }?.removePrefix("Bearer ")
        val principal = secret?.let { resolve(it) }
        if (principal != null) {
            context.principal(principal)
            return
        }
        val cause = if (secret == null) AuthenticationFailedCause.NoCredentials else AuthenticationFailedCause.InvalidCredentials
        context.challenge(API_KEY_AUTH, cause) { challenge, call ->
            call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", if (secret == null) "Missing or expired access token" else "Invalid or revoked API key"))
            challenge.complete()
        }
    }
}

fun Application.configureSecurity(jwtConfig: AppConfig.JwtConfig, apiKeys: suspend (String) -> ApiKeyPrincipal? = { null }) {
    install(Authentication) {
        register(ApiKeyAuthenticationProvider(ApiKeyAuthenticationProvider.Config(API_KEY_AUTH).apply { resolve = apiKeys }))
        jwt(JWT_AUTH) {
            realm = "voicecontrol"
            verifier(
                JWT.require(Algorithm.HMAC256(jwtConfig.secret))
                    .withIssuer(jwtConfig.issuer)
                    .withAudience(jwtConfig.audience)
                    .build(),
            )
            validate { credential ->
                val subject = credential.payload.subject
                if (subject != null && runCatching { UUID.fromString(subject) }.isSuccess) JWTPrincipal(credential.payload) else null
            }
            challenge { _, _ ->
                call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "Missing or expired access token"))
            }
        }
    }
}

/**
 * The authenticated user's id (only valid inside `authenticate(...)`). For an API key this is the key's
 * creator, still a member of the key's organization.
 */
val ApplicationCall.userId: UUID
    get() = principal<JWTPrincipal>()?.payload?.subject?.let(UUID::fromString)
        ?: principal<ApiKeyPrincipal>()?.actingUserId
        ?: throw DomainException.Unauthorized()

/** Set when the call is made with an API key. */
val ApplicationCall.keyContext: KeyContext?
    get() = principal<ApiKeyPrincipal>()?.let { KeyContext(it.key.id, it.key.orgId) }

/** Routes for signed-in people only (not API keys). */
fun ApplicationCall.requireUser() {
    if (principal<ApiKeyPrincipal>() != null) throw DomainException.Forbidden("This endpoint can't be used with an API key")
}

/** An API key must carry [scope]; signed-in people have every scope. */
fun ApplicationCall.requireScope(scope: ApiScope) {
    val key = principal<ApiKeyPrincipal>()?.key ?: return
    if (scope !in key.scopes) throw DomainException.Forbidden("This API key lacks the ${scope.id} scope")
}

/**
 * Organization the request works in: the API key's organization, or the `X-Org-Id` header the dashboard
 * sends when an organization is selected (null = personal flows).
 */
val ApplicationCall.orgContext: UUID?
    get() = principal<ApiKeyPrincipal>()?.key?.orgId
        ?: request.headers[ORG_HEADER]?.trim()?.takeIf { it.isNotEmpty() }?.let {
            runCatching { UUID.fromString(it) }.getOrElse { throw DomainException.Validation("$ORG_HEADER must be an organization id") }
        }

const val ORG_HEADER = "X-Org-Id"
