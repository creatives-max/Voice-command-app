package com.voicecontrol.core.network

import com.voicecontrol.core.model.ElementKind
import com.voicecontrol.core.model.FieldType
import com.voicecontrol.core.model.IntentKind
import com.voicecontrol.core.model.Language
import com.voicecontrol.core.model.ScreenElement
import com.voicecontrol.core.model.ScreenSnapshot
import com.voicecontrol.core.network.dto.InterpretRequestDto
import com.voicecontrol.core.network.dto.ScreenContextDto
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApiClientTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private class FakeSession(var token: String = "old") : BackendSession {
        var refreshed = 0
        override suspend fun baseUrl() = "https://api.test"
        override suspend fun accessToken() = token
        override suspend fun refreshTokens(): Boolean {
            refreshed++
            token = "new"
            return true
        }
    }

    private val request = InterpretRequestDto(
        ScreenContextDto.from(
            ScreenSnapshot(
                "com.app",
                elements = listOf(
                    ScreenElement("p", ElementKind.TEXT_FIELD, "Password", FieldType.PASSWORD, value = "secret", isSensitive = true),
                    ScreenElement("n", ElementKind.TEXT_FIELD, "Name", FieldType.NAME, value = "Rahul"),
                ),
            ),
        ),
        "n", "rahul", Language.ENGLISH, null, true,
    )

    @Test
    fun `refreshes token once on 401 and never sends sensitive values`() = runTest {
        val session = FakeSession()
        val bodies = mutableListOf<String>()
        val engine = MockEngine { req ->
            bodies += String((req.body as io.ktor.http.content.OutgoingContent.ByteArrayContent).bytes())
            if (req.headers[HttpHeaders.Authorization] == "Bearer old") {
                respond("""{"error":"unauthorized","message":"expired"}""", HttpStatusCode.Unauthorized, json)
            } else {
                respond("""{"intent":"FILL","targetId":"n","value":"Rahul","source":"anthropic"}""", HttpStatusCode.OK, json)
            }
        }
        val api = AiApi(ApiClient(createHttpClient(engine, debug = false), session))
        val result = api.interpret(request)
        assertEquals(IntentKind.FILL, result.intent)
        assertEquals(1, session.refreshed)
        assertFalse(bodies.any { it.contains("secret") })
        assertTrue(bodies.first().contains("\"utterance\":\"rahul\""))
    }

    @Test
    fun `errors map to ApiException with backend code`() = runTest {
        val engine = MockEngine { respond("""{"error":"validation_error","message":"utterance must not be blank"}""", HttpStatusCode.BadRequest, json) }
        val api = AiApi(ApiClient(createHttpClient(engine, debug = false), FakeSession()))
        val e = assertFailsWith<ApiException> { api.interpret(request) }
        assertEquals("validation_error", e.code)
        assertEquals(400, e.status)
    }
}
