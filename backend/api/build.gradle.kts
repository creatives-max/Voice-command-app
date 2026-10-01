plugins {
    application
    alias(libs.plugins.ktor)
}

application {
    mainClass.set("com.voicecontrol.api.ApplicationKt")
}

ktor {
    fatJar {
        archiveFileName.set("voicecontrol-backend.jar")
    }
}

dependencies {
    implementation(project(":infrastructure"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.call.id)
    implementation(libs.ktor.server.request.validation)
    implementation(libs.ktor.server.rate.limit)
    implementation(libs.ktor.server.openapi)
    implementation(libs.ktor.server.swagger)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.logback.classic)
    implementation(libs.logstash.encoder)
    implementation(libs.otel.api)
    implementation(libs.otel.sdk)
    implementation(libs.otel.sdk.autoconfigure)
    implementation(libs.otel.exporter.otlp)
    implementation(libs.otel.exporter.logging)
    implementation(libs.otel.ktor)
    implementation(libs.otel.logback.mdc)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.testcontainers.postgres)
    testImplementation(libs.testcontainers.junit)
}
