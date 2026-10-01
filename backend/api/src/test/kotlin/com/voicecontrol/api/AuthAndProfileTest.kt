package com.voicecontrol.api

import com.voicecontrol.api.routes.AuthResponse
import com.voicecontrol.domain.user.Profile
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AuthAndProfileTest {
    @Test
    fun `register, login, refresh rotation and logout`() = testApplication {
        application { voiceControl(TestEnvironment.services) }
        val client = jsonClient()
        val email = "rahul-${System.nanoTime()}@example.com"
        val registered = client.registerUser(email)
        assertEquals(email, registered.user.email)

        val duplicate = client.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("email" to email.uppercase(), "password" to "secret123"))
        }
        assertEquals(HttpStatusCode.Conflict, duplicate.status)

        val wrong = client.post("/v1/auth/login") { contentType(ContentType.Application.Json); setBody(mapOf("email" to email, "password" to "nope12345")) }
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)

        val login: AuthResponse = client.post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("email" to email, "password" to "secret123"))
        }.body()
        assertEquals(HttpStatusCode.OK, client.get("/v1/me") { bearerAuth(login.accessToken) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/me").status)

        val refreshed: AuthResponse = client.post("/v1/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("refreshToken" to login.refreshToken))
        }.body()
        assertNotEquals(login.refreshToken, refreshed.refreshToken)
        // The old refresh token was rotated away.
        val reuse = client.post("/v1/auth/refresh") { contentType(ContentType.Application.Json); setBody(mapOf("refreshToken" to login.refreshToken)) }
        assertEquals(HttpStatusCode.Unauthorized, reuse.status)

        client.post("/v1/auth/logout") {
            bearerAuth(refreshed.accessToken)
            contentType(ContentType.Application.Json)
            setBody(mapOf("everywhere" to true))
        }
        val afterLogout = client.post("/v1/auth/refresh") { contentType(ContentType.Application.Json); setBody(mapOf("refreshToken" to refreshed.refreshToken)) }
        assertEquals(HttpStatusCode.Unauthorized, afterLogout.status)
    }

    @Test
    fun `weak passwords are rejected`() = testApplication {
        application { voiceControl(TestEnvironment.services) }
        val response = jsonClient().post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("email" to "weak-${System.nanoTime()}@example.com", "password" to "short"))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `profile round trip with validation and cache invalidation`() = testApplication {
        application { voiceControl(TestEnvironment.services) }
        val client = jsonClient()
        val auth = client.registerUser()
        val empty: Profile = client.get("/v1/profile") { bearerAuth(auth.accessToken) }.body()
        assertEquals(Profile(), empty)

        val saved = client.put("/v1/profile") {
            bearerAuth(auth.accessToken)
            contentType(ContentType.Application.Json)
            setBody(Profile(fullName = " Rahul Sharma ", email = "Rahul@Example.com", phone = "+91 98765 43210", pincode = "110001", city = "Delhi"))
        }
        assertEquals(HttpStatusCode.OK, saved.status, saved.bodyAsText())
        val fetched: Profile = client.get("/v1/profile") { bearerAuth(auth.accessToken) }.body()
        assertEquals("Rahul Sharma", fetched.fullName)
        assertEquals("rahul@example.com", fetched.email)
        assertEquals("+919876543210", fetched.phone)

        val bad = client.put("/v1/profile") {
            bearerAuth(auth.accessToken)
            contentType(ContentType.Application.Json)
            setBody(Profile(pincode = "12"))
        }
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        assertTrue(bad.bodyAsText().contains("PIN code"))
    }
}
