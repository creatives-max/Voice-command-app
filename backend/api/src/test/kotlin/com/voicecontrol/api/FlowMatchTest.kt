package com.voicecontrol.api

import com.voicecontrol.api.routes.CreateFlowRequest
import com.voicecontrol.api.routes.FlowDto
import com.voicecontrol.api.routes.MatchRequest
import com.voicecontrol.api.routes.MatchResponse
import com.voicecontrol.api.routes.UpdateFlowRequest
import com.voicecontrol.domain.ai.ElementKind
import com.voicecontrol.domain.ai.FieldType
import com.voicecontrol.domain.flow.FlowStep
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FlowMatchTest {
    private val signature = "com.bank.app|KycActivity|button:submit|text_field/address:address|text_field/name:full name|text_field/pincode:pin code"

    private suspend fun HttpClient.match(token: String, sig: String): MatchResponse = post("/v1/flows/match") {
        bearerAuth(token); contentType(ContentType.Application.Json); setBody(MatchRequest("com.bank.app", sig))
    }.body()

    @Test
    fun `exact and near screens match, edited version is returned, other screens do not`() = testApplication {
        application { voiceControl(TestEnvironment.services) }
        val client = jsonClient()
        val token = client.registerUser().accessToken
        val steps = listOf(
            FlowStep("a", 0, "vid:name", "Full name", ElementKind.TEXT_FIELD, FieldType.NAME),
            FlowStep("b", 1, "vid:pin", "PIN code", ElementKind.TEXT_FIELD, FieldType.PINCODE),
        )
        val flow: FlowDto = client.post("/v1/flows") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody(CreateFlowRequest("com.bank.app", "KYC", signature, steps))
        }.body()

        // Nothing saved for unrelated screens.
        assertNull(client.match(token, "com.bank.app|TransferActivity|button:send money|text_field/amount:amount").flow)

        val exact = client.match(token, signature)
        assertEquals(flow.id, exact.flow?.id)
        assertEquals("exact", exact.matchedBy)

        // Dashboard edit; the next run must get the edited version (cache invalidated by the event).
        client.put("/v1/flows/${flow.id}") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody(UpdateFlowRequest(1, steps = steps.map { if (it.id == "a") it.copy(question = "Aapka poora naam?") else it }))
        }
        var edited: MatchResponse? = null
        repeat(50) {
            edited = client.match(token, signature)
            if (edited?.flow?.version == 2) return@repeat
            delay(100)
        }
        assertEquals("Aapka poora naam?", edited!!.flow!!.steps.first().question)

        // Labels changed slightly in an app update: still matched through pgvector.
        val renamed = "com.bank.app|KycActivity|button:submit|text_field/address:address|text_field/name:full name|text_field/pincode:pincode"
        var near: MatchResponse? = null
        repeat(50) {
            near = client.match(token, renamed)
            if (near?.flow != null) return@repeat
            delay(100)
        }
        assertEquals(flow.id, near!!.flow!!.id)
        assertEquals("vector", near!!.matchedBy)
    }
}
