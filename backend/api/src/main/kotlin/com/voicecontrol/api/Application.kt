package com.voicecontrol.api

import com.voicecontrol.api.plugins.configureHttp
import com.voicecontrol.api.plugins.Telemetry
import com.voicecontrol.api.plugins.configureSecurity
import com.voicecontrol.api.plugins.configureTelemetry
import com.voicecontrol.api.routes.historyRoutes
import com.voicecontrol.api.routes.aiRoutes
import com.voicecontrol.api.routes.automationRoutes
import com.voicecontrol.api.routes.authRoutes
import com.voicecontrol.api.routes.flowRoutes
import com.voicecontrol.api.routes.profileRoutes
import com.voicecontrol.infrastructure.config.AppConfig
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.openapi.openAPI
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

fun main() {
    val config = AppConfig.fromEnv()
    val telemetry = Telemetry.init(config.serviceName, config.otlpEndpoint)
    val services = Bootstrap.create(config)
    if (config.schedulerIntervalSeconds > 0) services.startScheduler(config.schedulerIntervalSeconds * 1_000L)
    embeddedServer(Netty, port = config.port, host = "0.0.0.0") {
        configureTelemetry(telemetry)
        voiceControl(services)
        monitor.subscribe(ApplicationStopped) { services.close() }
    }.start(wait = true)
}

fun Application.voiceControl(services: Services) {
    configureHttp(services.config.corsOrigins)
    configureSecurity(services.config.jwt)
    routing {
        get("/health") { call.respond(HttpStatusCode.OK, Health("ok", services.ai.providerName)) }
        get("/ready") {
            val ready = services.readiness()
            call.respond(if (ready.ok) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable, ready)
        }
        openAPI(path = "openapi", swaggerFile = "openapi/documentation.yaml")
        swaggerUI(path = "docs", swaggerFile = "openapi/documentation.yaml")
        authRoutes(services.auth, services.rateLimiter, services.config.authRateLimitPerMinute)
        profileRoutes(services.profiles)
        flowRoutes(services.flows, services.matcher)
        aiRoutes(services.ai, services.rateLimiter)
        historyRoutes(services.history)
        automationRoutes(services.devices, services.triggers, services.runRequests, services::flowApp)
    }
}

@Serializable
data class Health(val status: String, val llmProvider: String)

@Serializable
data class Readiness(val ok: Boolean, val database: Boolean, val redis: Boolean)
