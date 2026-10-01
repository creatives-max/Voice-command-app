package com.voicecontrol.api

import com.voicecontrol.api.routes.AuthResponse
import com.voicecontrol.api.routes.SessionDto
import com.voicecontrol.api.routes.UserDto
import com.voicecontrol.application.auth.AuthService
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountSecurityApiTest {
    private val chrome = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Safari/537.36"
    private val phone = "VoiceControl-Android/1.4.0 (Pixel 8; Android 15)"

    private suspend fun HttpClient.login(email: String, password: String, agent: String): HttpResponse = post("/v1/auth/login") {
        header(HttpHeaders.UserAgent, agent)
        contentType(ContentType.Application.Json)
        setBody(mapOf("email" to email, "password" to password))
    }

    private suspend fun HttpClient.refresh(token: String): HttpResponse = post("/v1/auth/refresh") {
        contentType(ContentType.Application.Json)
        setBody(mapOf("refreshToken" to token))
    }

    @Test
    fun `sessions are listed with their client, survive refresh and can be signed out`() = testApplication {
        application { voiceControl(TestEnvironment.services) }
        val client = jsonClient()
        val email = "sessions-${UUID.randomUUID()}@example.com"
        client.registerUser(email)
        val browser: AuthResponse = client.login(email, "secret123", chrome).body()
        val app: AuthResponse = client.login(email, "secret123", phone).body()

        val sessions: List<SessionDto> = client.get("/v1/me/sessions") { bearerAuth(browser.accessToken) }.body()
        // Registering also signed in (without a recognisable client).
        assertEquals(3, sessions.size)
        val mine = sessions.single { it.current }
        assertEquals("Chrome on Windows", mine.client)
        assertTrue(sessions.any { it.client == "VoiceControl app 1.4.0 on Android" })

        // Refreshing keeps the same session (id and client) with a new token.
        val refreshed: AuthResponse = client.refresh(browser.refreshToken).body()
        val after: List<SessionDto> = client.get("/v1/me/sessions") { bearerAuth(refreshed.accessToken) }.body()
        assertEquals(3, after.size)
        assertEquals(mine.id, after.single { it.current }.id)
        assertEquals("Chrome on Windows", after.single { it.current }.client)
        assertEquals(mine.createdAt, after.single { it.current }.createdAt)

        // Sign out the phone from the browser: its refresh token stops working.
        val phoneSession = after.single { it.client?.startsWith("VoiceControl app") == true }
        assertEquals(HttpStatusCode.NoContent, client.delete("/v1/me/sessions/${phoneSession.id}") { bearerAuth(refreshed.accessToken) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.refresh(app.refreshToken).status)
        assertEquals(HttpStatusCode.NotFound, client.delete("/v1/me/sessions/${phoneSession.id}") { bearerAuth(refreshed.accessToken) }.status)

        // Someone else can't see or end these sessions.
        val other = client.registerUser()
        assertEquals(HttpStatusCode.NotFound, client.delete("/v1/me/sessions/${mine.id}") { bearerAuth(other.accessToken) }.status)
        val theirs: List<SessionDto> = client.get("/v1/me/sessions") { bearerAuth(other.accessToken) }.body()
        assertTrue(theirs.none { it.id == mine.id })

        // Logging out removes the session from the list.
        client.post("/v1/auth/logout") {
            bearerAuth(refreshed.accessToken)
            contentType(ContentType.Application.Json)
            setBody(mapOf("refreshToken" to refreshed.refreshToken))
        }
        val last: List<SessionDto> = client.get("/v1/me/sessions") { bearerAuth(other.accessToken) }.body()
        assertTrue(last.none { it.id == mine.id })
    }

    @Test
    fun `changing the password checks the old one and signs out other sessions`() = testApplication {
        application { voiceControl(TestEnvironment.services) }
        val client = jsonClient()
        val email = "password-${UUID.randomUUID()}@example.com"
        val first = client.registerUser(email)
        val second: AuthResponse = client.login(email, "secret123", phone).body()
        val me: UserDto = client.get("/v1/me") { bearerAuth(first.accessToken) }.body()
        assertNull(me.passwordChangedAt)

        suspend fun change(current: String, new: String) = client.post("/v1/me/password") {
            bearerAuth(first.accessToken)
            contentType(ContentType.Application.Json)
            setBody(mapOf("currentPassword" to current, "newPassword" to new))
        }
        assertEquals(HttpStatusCode.Forbidden, change("wrong-one1", "newsecret456").status)
        assertEquals(HttpStatusCode.BadRequest, change("secret123", "short").status)
        assertEquals(HttpStatusCode.BadRequest, change("secret123", "secret123").status)
        assertEquals(HttpStatusCode.NoContent, change("secret123", "newsecret456").status)

        // This session stays; the phone was signed out; only the new password works.
        assertEquals(HttpStatusCode.OK, client.refresh(first.refreshToken).status)
        assertEquals(HttpStatusCode.Unauthorized, client.refresh(second.refreshToken).status)
        assertEquals(HttpStatusCode.Unauthorized, client.login(email, "secret123", chrome).status)
        assertEquals(HttpStatusCode.OK, client.login(email, "newsecret456", chrome).status)
        val changed: UserDto = client.get("/v1/me") { bearerAuth(first.accessToken) }.body()
        assertNotNull(changed.passwordChangedAt)

        // API keys can't change passwords.
        assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/me/password") { header("X-Api-Key", "vck_nope"); contentType(ContentType.Application.Json); setBody("{}") }.status)
    }

    @Test
    fun `an account is locked for a while after too many wrong passwords`() = testApplication {
        application { voiceControl(TestEnvironment.services) }
        val client = jsonClient()
        val email = "lockout-${UUID.randomUUID()}@example.com"
        client.registerUser(email)
        repeat(AuthService.MAX_FAILED_LOGINS - 1) {
            assertEquals(HttpStatusCode.Unauthorized, client.login(email, "wrong-pass$it", chrome).status)
        }
        val locked = client.login(email, "wrong-pass-last1", chrome)
        assertEquals(HttpStatusCode.TooManyRequests, locked.status)
        assertTrue("15 minutes" in locked.bodyAsText())
        // Even the right password waits until the lock ends.
        assertEquals(HttpStatusCode.TooManyRequests, client.login(email, "secret123", chrome).status)
        // Other accounts are not affected.
        val other = "lockout-other-${UUID.randomUUID()}@example.com"
        client.registerUser(other)
        assertEquals(HttpStatusCode.OK, client.login(other, "secret123", chrome).status)
    }
}
