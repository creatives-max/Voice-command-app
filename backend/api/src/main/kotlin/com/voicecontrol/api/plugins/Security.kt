package com.voicecontrol.api.plugins

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.voicecontrol.domain.common.DomainException
import com.voicecontrol.infrastructure.config.AppConfig
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import java.util.UUID

const val JWT_AUTH = "jwt"

fun Application.configureSecurity(jwtConfig: AppConfig.JwtConfig) {
    install(Authentication) {
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

/** The authenticated user's id (only valid inside `authenticate(JWT_AUTH)`). */
val ApplicationCall.userId: UUID
    get() = principal<JWTPrincipal>()?.payload?.subject?.let(UUID::fromString)
        ?: throw DomainException.Unauthorized()
