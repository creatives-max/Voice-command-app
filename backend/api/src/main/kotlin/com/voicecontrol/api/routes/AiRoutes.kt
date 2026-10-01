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
import com.voicecontrol.domain.ai.Language
import com.voicecontrol.domain.ai.VisionCommand
import kotlinx.serialization.Serializable
import java.util.Base64

/** Screenshot (JPEG, base64) of an app whose screen has no readable accessibility nodes. */
@Serializable
data class VisionRequest(val packageName: String, val imageBase64: String, val width: Int, val height: Int, val language: Language = Language.ENGLISH)

private const val VISION_PER_MINUTE = 20

/** Screen + speech → action. The phone never talks to an LLM directly; only this endpoint does. */
fun Route.aiRoutes(ai: AiService, limiter: RateLimiter, perMinute: Int = 120) {
    authenticate(JWT_AUTH) {
        route("/v1/ai") {
            post("/interpret") {
                if (!limiter.tryAcquire("ai:${call.userId}", perMinute, 60)) throw DomainException.RateLimited("Too many voice requests, slow down a little")
                call.respond(ai.interpret(call.receive<InterpretCommand>()))
            }
            post("/vision") {
                if (!limiter.tryAcquire("vision:${call.userId}", VISION_PER_MINUTE, 60)) throw DomainException.RateLimited("Too many screenshot requests")
                val body = call.receive<VisionRequest>()
                val jpeg = runCatching { Base64.getDecoder().decode(body.imageBase64) }.getOrElse { throw DomainException.Validation("imageBase64 is not valid base64") }
                call.respond(ai.detectElements(VisionCommand(body.packageName, jpeg, body.width, body.height, body.language)))
            }
        }
    }
}
