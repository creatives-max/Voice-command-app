package com.voicecontrol.api

import io.ktor.client.request.bearerAuth
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

class AiApiTest {
    private val requestJson = """{"screen":{"packageName":"com.shop","elements":[
          {"id":"vid:phone","kind":"TEXT_FIELD","label":"Mobile number","fieldType":"PHONE"},
          {"id":"vid:go","kind":"BUTTON","label":"Continue"}]},
          "currentFieldId":"vid:phone","utterance":"mera number nau aath saat chhe paanch chaar teen do ek shunya hai","language":"HINGLISH"}"""

    @Test
    fun `interpret requires auth and fills from hinglish speech`() = testApplication {
        application { voiceControl(TestEnvironment.services) }
        val token = jsonClient().registerUser().accessToken
        val anonymous = client.post("/v1/ai/interpret") { contentType(ContentType.Application.Json); setBody(requestJson) }
        assertEquals(HttpStatusCode.Unauthorized, anonymous.status)

        val response = client.post("/v1/ai/interpret") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(requestJson) }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val text = response.bodyAsText()
        assertTrue(text.contains("\"intent\":\"FILL\""), text)
        assertTrue(text.contains("\"value\":\"9876543210\""), text)

        val blank = client.post("/v1/ai/interpret") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"screen":{"packageName":"x","elements":[]},"utterance":"  "}""")
        }
        assertEquals(HttpStatusCode.BadRequest, blank.status)
        assertTrue(blank.bodyAsText().contains("validation_error"))
    }
}
