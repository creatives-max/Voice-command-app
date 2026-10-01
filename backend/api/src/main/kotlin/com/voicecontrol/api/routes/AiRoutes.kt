package com.voicecontrol.api.routes

import com.voicecontrol.api.plugins.JWT_AUTH
import com.voicecontrol.api.plugins.userId
import com.voicecontrol.application.ai.AiService
import com.voicecontrol.domain.common.DomainException
import com.voicecontrol.domain.event.RateLimiter
import com.voicecontrol.domain.ai.InterpretCommand
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/** Screen + speech → action. The phone never talks to an LLM directly; only this endpoint does. */
fun Route.aiRoutes(ai: AiService, limiter: RateLimiter, perMinute: Int = 120) {
    authenticate(JWT_AUTH) {
        route("/v1/ai") {
            post("/interpret") {
                if (!limiter.tryAcquire("ai:${call.userId}", perMinute, 60)) throw DomainException.RateLimited("Too many voice requests, slow down a little")
                call.respond(ai.interpret(call.receive<InterpretCommand>()))
            }
        }
    }
}
