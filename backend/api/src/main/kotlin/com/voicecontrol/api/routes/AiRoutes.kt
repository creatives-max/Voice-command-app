package com.voicecontrol.api.routes

import com.voicecontrol.application.ai.AiService
import com.voicecontrol.domain.ai.InterpretCommand
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/** Screen + speech → action. The phone never talks to an LLM directly; only this endpoint does. */
fun Route.aiRoutes(ai: AiService) {
    route("/v1/ai") {
        post("/interpret") {
            val command = call.receive<InterpretCommand>()
            call.respond(ai.interpret(command))
        }
    }
}
