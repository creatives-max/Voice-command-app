package com.voicecontrol.api

import com.voicecontrol.application.ai.AiService
import com.voicecontrol.application.ai.RulesProvider
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HealthTest {
    private val services = Services(ai = AiService(RulesProvider()))

    @Test
    fun `health endpoint returns ok`() = testApplication {
        application { voiceControl(services) }
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"rules\""))
    }

    @Test
    fun `interpret fills the current field from hinglish speech`() = testApplication {
        application { voiceControl(services) }
        val response = client.post("/v1/ai/interpret") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"screen":{"packageName":"com.shop","elements":[
                  {"id":"vid:phone","kind":"TEXT_FIELD","label":"Mobile number","fieldType":"PHONE"},
                  {"id":"vid:go","kind":"BUTTON","label":"Continue"}]},
                  "currentFieldId":"vid:phone","utterance":"mera number nau aath saat chhe paanch chaar teen do ek shunya hai","language":"HINGLISH"}""",
            )
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"intent\":\"FILL\""), body)
        assertTrue(body.contains("\"value\":\"9876543210\""), body)
    }

    @Test
    fun `malformed and invalid requests return 400 with error code`() = testApplication {
        application { voiceControl(services) }
        val bad = client.post("/v1/ai/interpret") { contentType(ContentType.Application.Json); setBody("{}") }
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        val blank = client.post("/v1/ai/interpret") {
            contentType(ContentType.Application.Json)
            setBody("""{"screen":{"packageName":"x","elements":[]},"utterance":"  "}""")
        }
        assertEquals(HttpStatusCode.BadRequest, blank.status)
        assertTrue(blank.bodyAsText().contains("validation_error"))
    }
}
