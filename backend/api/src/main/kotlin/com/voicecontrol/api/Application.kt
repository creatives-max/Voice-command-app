package com.voicecontrol.api

import com.voicecontrol.api.plugins.configureHttp
import com.voicecontrol.api.routes.aiRoutes
import com.voicecontrol.application.ai.AiService
import com.voicecontrol.infrastructure.ai.LlmProviderFactory
import com.voicecontrol.infrastructure.config.AppConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

/** Everything the HTTP layer needs; built once in [main] (composition root) or by tests. */
class Services(
    val ai: AiService,
)

fun main() {
    val config = AppConfig.fromEnv()
    val http = HttpClient(CIO) { engine { requestTimeout = config.llm.timeoutMillis * 3 } }
    val services = Services(
        ai = AiService(LlmProviderFactory.create(config.llm, http), timeoutMillis = config.llm.timeoutMillis),
    )
    embeddedServer(Netty, port = config.port, host = "0.0.0.0") {
        voiceControl(services, config.corsOrigins)
    }.start(wait = true)
}

fun Application.voiceControl(services: Services, corsOrigins: List<String> = emptyList()) {
    configureHttp(corsOrigins)
    routing {
        get("/health") { call.respond(HttpStatusCode.OK, Health("ok", services.ai.providerName)) }
        aiRoutes(services.ai)
    }
}

@Serializable
data class Health(val status: String, val llmProvider: String)
