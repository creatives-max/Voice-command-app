package com.voicecontrol.api

import com.voicecontrol.api.routes.CreateFlowRequest
import com.voicecontrol.api.routes.FlowDto
import com.voicecontrol.api.routes.FlowVersionDto
import com.voicecontrol.api.routes.UpdateFlowRequest
import com.voicecontrol.domain.ai.ElementKind
import com.voicecontrol.domain.ai.FieldType
import com.voicecontrol.domain.flow.FlowStep
import com.voicecontrol.domain.flow.StepAction
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlowApiTest {
    private val steps = listOf(
        FlowStep("s1", 0, "vid:name", "Full name", ElementKind.TEXT_FIELD, FieldType.NAME),
        FlowStep("s2", 1, "vid:phone", "Mobile", ElementKind.TEXT_FIELD, FieldType.PHONE),
        FlowStep("s3", 2, "vid:go", "Continue", ElementKind.BUTTON, action = StepAction.CLICK),
    )
    private val create = CreateFlowRequest("com.shop.app", "Shop · Signup", "com.shop.app|Signup|button:continue|text_field/name:full name|text_field/phone:mobile", steps)

    @Test
    fun `device upload is idempotent and dashboard edits create versions`() = testApplication {
        application { voiceControl(TestEnvironment.services) }
        val client = jsonClient()
        val token = client.registerUser().accessToken

        val created = client.post("/v1/flows") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(create) }
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val flow: FlowDto = created.body()
        assertEquals(1, flow.version)
        val again: FlowDto = client.post("/v1/flows") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(create) }.body()
        assertEquals(flow.id, again.id)

        // Dashboard edit: reorder (phone first), custom question, rule, default, skip, help video.
        val edited = listOf(
            steps[1].copy(order = 0, question = "Apna mobile number bataiye", rules = listOf("required", "digits:10"), helpVideoUrl = "https://example.com/help.mp4"),
            steps[0].copy(order = 1, defaultValue = "Guest", skip = true),
            steps[2].copy(order = 2),
        )
        val updated: FlowDto = client.put("/v1/flows/${flow.id}") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody(UpdateFlowRequest(expectedVersion = 1, steps = edited, changeNote = "Hinglish question"))
        }.body()
        assertEquals(2, updated.version)
        assertEquals(listOf("s2", "s1", "s3"), updated.steps.map { it.id })
        assertEquals("Apna mobile number bataiye", updated.steps[0].question)

        // Stale writes are rejected.
        val stale = client.put("/v1/flows/${flow.id}") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody(UpdateFlowRequest(expectedVersion = 1, steps = steps))
        }
        assertEquals(HttpStatusCode.Conflict, stale.status)

        val versions: List<FlowVersionDto> = client.get("/v1/flows/${flow.id}/versions") { bearerAuth(token) }.body()
        assertEquals(listOf(2, 1), versions.map { it.version })

        val rolledBack: FlowDto = client.post("/v1/flows/${flow.id}/rollback") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody(mapOf("version" to 1))
        }.body()
        assertEquals(3, rolledBack.version)
        assertEquals(listOf("s1", "s2", "s3"), rolledBack.steps.map { it.id })

        assertTrue(client.get("/v1/flows/apps") { bearerAuth(token) }.bodyAsText().contains("com.shop.app"))

        // Events were consumed and acknowledged by the Redis Streams consumer group.
        repeat(50) { if (TestEnvironment.services.eventBus.pendingCount() == 0L) return@repeat; delay(100) }
        assertEquals(0L, TestEnvironment.services.eventBus.pendingCount())

        assertEquals(HttpStatusCode.NoContent, client.delete("/v1/flows/${flow.id}") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/flows/${flow.id}") { bearerAuth(token) }.status)
    }

    @Test
    fun `invalid edits and other users' flows are rejected`() = testApplication {
        application { voiceControl(TestEnvironment.services) }
        val client = jsonClient()
        val owner = client.registerUser().accessToken
        val stranger = client.registerUser().accessToken
        val flow: FlowDto = client.post("/v1/flows") { bearerAuth(owner); contentType(ContentType.Application.Json); setBody(create) }.body()

        assertEquals(HttpStatusCode.NotFound, client.get("/v1/flows/${flow.id}") { bearerAuth(stranger) }.status)

        val badRule = client.put("/v1/flows/${flow.id}") {
            bearerAuth(owner); contentType(ContentType.Application.Json)
            setBody(UpdateFlowRequest(1, steps = listOf(steps[0].copy(rules = listOf("regex:[unclosed")))))
        }
        assertEquals(HttpStatusCode.BadRequest, badRule.status)
        val badVideo = client.put("/v1/flows/${flow.id}") {
            bearerAuth(owner); contentType(ContentType.Application.Json)
            setBody(UpdateFlowRequest(1, steps = listOf(steps[0].copy(helpVideoUrl = "javascript:alert(1)"))))
        }
        assertEquals(HttpStatusCode.BadRequest, badVideo.status)
        val pwdDefault = client.put("/v1/flows/${flow.id}") {
            bearerAuth(owner); contentType(ContentType.Application.Json)
            setBody(UpdateFlowRequest(1, steps = listOf(FlowStep("p", 0, "vid:pwd", "Password", ElementKind.TEXT_FIELD, FieldType.PASSWORD, defaultValue = "x"))))
        }
        assertEquals(HttpStatusCode.BadRequest, pwdDefault.status)
    }
}
