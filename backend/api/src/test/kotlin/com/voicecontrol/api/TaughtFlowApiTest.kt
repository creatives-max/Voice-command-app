package com.voicecontrol.api

import com.voicecontrol.api.routes.CreateFlowRequest
import com.voicecontrol.api.routes.FlowDto
import com.voicecontrol.api.routes.FlowVersionDto
import com.voicecontrol.domain.ai.ElementKind
import com.voicecontrol.domain.flow.FlowStep
import com.voicecontrol.domain.flow.StepAction
import com.voicecontrol.domain.flow.VersionSource
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class TaughtFlowApiTest {
    @Test
    fun `flows taught by demonstration keep their source, with screen and app changes`() = testApplication {
        application { voiceControl(TestEnvironment.services) }
        val client = jsonClient()
        val token = client.registerUser().accessToken
        val unique = UUID.randomUUID().toString().take(8)
        val steps = listOf(
            FlowStep("rec-0-a", 0, "vid:name", "Name", ElementKind.TEXT_FIELD, defaultValue = "Asha"),
            FlowStep("rec-0-b", 1, "vid:next", "Next", ElementKind.BUTTON, action = StepAction.CLICK),
            FlowStep("rec-1-screen", 2, "", "Address", ElementKind.BUTTON, action = StepAction.NEXT_SCREEN, appPackage = "com.taught$unique.app", waitSeconds = 15),
            FlowStep("rec-1-c", 3, "vid:city", "City", ElementKind.TEXT_FIELD),
            FlowStep("rec-2-screen", 4, "", "Pay", ElementKind.BUTTON, action = StepAction.OPEN_APP, appPackage = "com.pay$unique.app", waitSeconds = 15),
            FlowStep("rec-2-d", 5, "vid:pay", "Pay", ElementKind.BUTTON, action = StepAction.CLICK),
        )
        val taught: FlowDto = client.post("/v1/flows") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody(CreateFlowRequest("com.taught$unique.app", "Shop · Sign up", "sig-$unique", steps, source = "RECORDED"))
        }.body()
        assertEquals(6, taught.steps.size)
        val versions: List<FlowVersionDto> = client.get("/v1/flows/${taught.id}/versions") { bearerAuth(token) }.body()
        assertEquals(VersionSource.RECORDED, versions.single().source)

        val spoken: FlowDto = client.post("/v1/flows") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody(CreateFlowRequest("com.taught$unique.app", "Other", "sig2-$unique", steps.take(2)))
        }.body()
        assertEquals(VersionSource.DEVICE, client.get("/v1/flows/${spoken.id}/versions") { bearerAuth(token) }.body<List<FlowVersionDto>>().single().source)
    }
}
