package com.voicecontrol.api

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HealthTest {
    @Test
    fun `health, readiness and openapi docs are served`() = testApplication {
        application { voiceControl(TestEnvironment.services) }
        assertEquals(HttpStatusCode.OK, client.get("/health").status)
        val ready = client.get("/ready")
        assertEquals(HttpStatusCode.OK, ready.status, ready.bodyAsText())
        val spec = client.get("/openapi/documentation.yaml")
        assertEquals(HttpStatusCode.OK, client.get("/docs").status)
        assertTrue(client.get("/docs/documentation.yaml").bodyAsText().contains("VoiceControl API") || spec.bodyAsText().isNotEmpty())
    }
}
