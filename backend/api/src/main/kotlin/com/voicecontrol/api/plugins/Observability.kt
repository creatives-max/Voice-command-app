package com.voicecontrol.api.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.instrumentation.ktor.v3_0.KtorServerTelemetry
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk

/**
 * OpenTelemetry tracing. Export is enabled by `OTEL_EXPORTER_OTLP_ENDPOINT` (e.g. the collector in
 * docker-compose / Kubernetes); without it, spans are still created (trace ids in logs) but not exported.
 */
object Telemetry {
    fun init(serviceName: String, otlpEndpoint: String?): OpenTelemetry =
        AutoConfiguredOpenTelemetrySdk.builder()
            .addPropertiesSupplier {
                buildMap {
                    put("otel.service.name", serviceName)
                    if (otlpEndpoint == null) {
                        put("otel.traces.exporter", "none")
                        put("otel.metrics.exporter", "none")
                        put("otel.logs.exporter", "none")
                    } else {
                        put("otel.exporter.otlp.endpoint", otlpEndpoint)
                        put("otel.metrics.exporter", "otlp")
                        put("otel.logs.exporter", "none")
                    }
                }
            }
            .setResultAsGlobal()
            .build()
            .openTelemetrySdk
}

fun Application.configureTelemetry(openTelemetry: OpenTelemetry) {
    install(KtorServerTelemetry) {
        setOpenTelemetry(openTelemetry)
    }
}
