package com.voicecontrol.api.routes

import com.voicecontrol.api.plugins.JWT_AUTH
import com.voicecontrol.api.plugins.userId
import com.voicecontrol.application.auth.AuthService
import com.voicecontrol.domain.common.DomainException
import com.voicecontrol.domain.event.RateLimiter
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.authRoutes(auth: AuthService, limiter: RateLimiter, perMinute: Int = 10) {
    route("/v1/auth") {
        post("/register") {
            throttle(limiter, call, "register", perMinute)
            val body = call.receive<RegisterRequest>()
            call.respond(HttpStatusCode.Created, AuthResponse.from(auth.register(body.email, body.password, body.name)))
        }
        post("/login") {
            throttle(limiter, call, "login", perMinute * 2)
            val body = call.receive<LoginRequest>()
            call.respond(AuthResponse.from(auth.login(body.email, body.password)))
        }
        post("/refresh") {
            val body = call.receive<RefreshRequest>()
            call.respond(AuthResponse.from(auth.refresh(body.refreshToken)))
        }
        authenticate(JWT_AUTH) {
            post("/logout") {
                val body = call.receive<LogoutRequest>()
                auth.logout(body.refreshToken, call.userId, body.everywhere)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
    authenticate(JWT_AUTH) {
        get("/v1/me") { call.respond(UserDto.from(auth.me(call.userId))) }
    }
}

/** Brute-force protection for credential endpoints (per client IP, per minute). */
private suspend fun throttle(limiter: RateLimiter, call: ApplicationCall, action: String, perMinute: Int) {
    if (!limiter.tryAcquire("$action:${call.request.origin.remoteHost}", perMinute, 60)) {
        throw DomainException.RateLimited("Too many attempts, please wait a minute")
    }
}
