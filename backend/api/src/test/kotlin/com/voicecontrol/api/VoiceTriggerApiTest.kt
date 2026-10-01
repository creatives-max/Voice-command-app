package com.voicecontrol.api

import com.voicecontrol.api.routes.AppOpenRunRequest
import com.voicecontrol.api.routes.CreateFlowRequest
import com.voicecontrol.api.routes.FlowDto
import com.voicecontrol.api.routes.RunRequestDto
import com.voicecontrol.api.routes.TriggerDto
import com.voicecontrol.api.routes.TriggerRequest
import com.voicecontrol.domain.ai.ElementKind
import com.voicecontrol.domain.ai.FieldType
import com.voicecontrol.domain.automation.RunSource
import com.voicecontrol.domain.automation.TriggerType
import com.voicecontrol.domain.flow.FlowStep
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceTriggerApiTest {
    private fun flow(name: String) = CreateFlowRequest(
        "com.power.app", name, "sig-${UUID.randomUUID()}",
        listOf(FlowStep("s1", 0, "vid:c", "Consumer number", ElementKind.TEXT_FIELD, FieldType.NUMBER)),
    )

    private suspend fun HttpClient.send(method: String, path: String, token: String, body: Any? = null): HttpResponse {
        val block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            bearerAuth(token)
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        return when (method) {
            "GET" -> get(path, block)
            "POST" -> post(path, block)
            "PUT" -> put(path, block)
            else -> delete(path, block)
        }
    }

    @Test
    fun `voice shortcuts are unique per account, reach the phone and report voice runs`() = testApplication {
        application { voiceControl(TestEnvironment.services) }
        val client = jsonClient()
        val token = client.registerUser().accessToken
        val bill: FlowDto = client.send("POST", "/v1/flows", token, flow("Electricity bill")).body()
        val other: FlowDto = client.send("POST", "/v1/flows", token, flow("Water bill")).body()
        val deviceId = UUID.randomUUID().toString()
        client.send("POST", "/v1/devices", token, mapOf("id" to deviceId, "name" to "Phone"))

        val created = client.send("POST", "/v1/flows/${bill.id}/triggers", token, TriggerRequest(TriggerType.VOICE, phrase = "  Pay   electricity bill "))
        assertEquals(HttpStatusCode.Created, created.status)
        val voice: TriggerDto = created.body()
        assertEquals("Pay electricity bill", voice.phrase)

        // The same phrase (ignoring case and punctuation) can't run another flow.
        val dup = client.send("POST", "/v1/flows/${other.id}/triggers", token, TriggerRequest(TriggerType.VOICE, phrase = "pay electricity bill!"))
        assertEquals(HttpStatusCode.Conflict, dup.status)
        assertEquals(HttpStatusCode.BadRequest, client.send("POST", "/v1/flows/${other.id}/triggers", token, TriggerRequest(TriggerType.VOICE, phrase = "Stop")).status)
        assertEquals(HttpStatusCode.BadRequest, client.send("POST", "/v1/flows/${other.id}/triggers", token, TriggerRequest(TriggerType.VOICE)).status)
        // Another account may use it.
        val stranger = client.registerUser().accessToken
        val theirs: FlowDto = client.send("POST", "/v1/flows", stranger, flow("Theirs")).body()
        assertEquals(HttpStatusCode.Created, client.send("POST", "/v1/flows/${theirs.id}/triggers", stranger, TriggerRequest(TriggerType.VOICE, phrase = "Pay electricity bill")).status)

        // The phone gets the shortcut with the flow's name and app.
        val onPhone: List<TriggerDto> = client.send("GET", "/v1/devices/$deviceId/triggers", token).body()
        val shortcut = onPhone.single { it.type == TriggerType.VOICE }
        assertEquals("Pay electricity bill", shortcut.phrase)
        assertEquals("Electricity bill", shortcut.flowName)
        assertEquals("com.power.app", shortcut.appPackage)

        val run: RunRequestDto = client.send("POST", "/v1/devices/$deviceId/app-open-runs", token, AppOpenRunRequest(bill.id, voice.id, RunSource.VOICE)).body()
        assertEquals(RunSource.VOICE, run.source)
        val badSource = client.send("POST", "/v1/devices/$deviceId/app-open-runs", token, AppOpenRunRequest(bill.id, voice.id, RunSource.SCHEDULE))
        assertEquals(HttpStatusCode.BadRequest, badSource.status)

        // Renaming keeps it unique; turning it into an app-open trigger drops the phrase.
        val renamed: TriggerDto = client.send("PUT", "/v1/triggers/${voice.id}", token, TriggerRequest(TriggerType.VOICE, phrase = "बिजली का बिल")).body()
        assertEquals("बिजली का बिल", renamed.phrase)
        val appOpen: TriggerDto = client.send("PUT", "/v1/triggers/${voice.id}", token, TriggerRequest(TriggerType.APP_OPEN)).body()
        assertNull(appOpen.phrase)
        // The old phrase is free again.
        assertEquals(HttpStatusCode.Created, client.send("POST", "/v1/flows/${other.id}/triggers", token, TriggerRequest(TriggerType.VOICE, phrase = "Pay electricity bill")).status)

        // Disabled shortcuts don't reach the phone.
        val water = client.send("GET", "/v1/flows/${other.id}/triggers", token).body<List<TriggerDto>>().single()
        client.send("PUT", "/v1/triggers/${water.id}", token, TriggerRequest(TriggerType.VOICE, enabled = false, phrase = "Pay electricity bill"))
        val after: List<TriggerDto> = client.send("GET", "/v1/devices/$deviceId/triggers", token).body()
        assertTrue(after.none { it.type == TriggerType.VOICE })
    }
}
