package com.voicecontrol.api

import com.voicecontrol.api.routes.ApiKeyDto
import com.voicecontrol.api.routes.AuditEntryDto
import com.voicecontrol.api.routes.CreateApiKeyRequest
import com.voicecontrol.api.routes.CreateOrgRequest
import com.voicecontrol.api.routes.OrgDto
import com.voicecontrol.api.routes.OrgUsageDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
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
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TenantApiTest {
    private suspend fun HttpClient.post(path: String, token: String, body: Any? = null): HttpResponse = post(path) {
        bearerAuth(token)
        if (body != null) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }

    @Test
    fun `api keys expire and rotate, audit exports as csv, usage and key limits`() = testApplication {
        application { voiceControl(TestEnvironment.services) }
        val client = jsonClient()
        val admin = client.registerUser().accessToken
        val org: OrgDto = client.post("/v1/orgs", admin, CreateOrgRequest("Tenant ${UUID.randomUUID().toString().take(6)}")).body()
        val keys = "/v1/orgs/${org.id}/api-keys"

        assertEquals(HttpStatusCode.BadRequest, client.post(keys, admin, CreateApiKeyRequest("Bad", listOf("flows:read"), expiresInDays = 0)).status)
        assertEquals(HttpStatusCode.BadRequest, client.post(keys, admin, CreateApiKeyRequest("Bad", listOf("flows:read"), expiresInDays = 400)).status)
        val expiring: ApiKeyDto = client.post(keys, admin, CreateApiKeyRequest("CI", listOf("flows:read"), expiresInDays = 30)).body()
        assertNotNull(expiring.expiresAt)
        assertFalse(expiring.expired)
        suspend fun withKey(secret: String) = client.get("/v1/flows") { header("X-Api-Key", secret) }.status
        assertEquals(HttpStatusCode.OK, withKey(expiring.secret!!))

        // Rotation: new secret works, the old one stops at once.
        val rotated: ApiKeyDto = client.post("$keys/${expiring.id}/rotate", admin).body()
        assertEquals(expiring.id, rotated.id)
        assertNotEquals(expiring.prefix, rotated.prefix)
        assertNotNull(rotated.rotatedAt)
        assertEquals(HttpStatusCode.Unauthorized, withKey(expiring.secret!!))
        assertEquals(HttpStatusCode.OK, withKey(rotated.secret!!))

        // An expired key is refused.
        TestEnvironment.services.database.tx {
            prepareStatement("UPDATE api_keys SET expires_at = now() - interval '1 minute' WHERE id = ?").use {
                it.setObject(1, UUID.fromString(rotated.id))
                it.executeUpdate()
            }
        }
        assertEquals(HttpStatusCode.Unauthorized, withKey(rotated.secret!!))
        assertTrue(client.get(keys) { bearerAuth(admin) }.body<List<ApiKeyDto>>().single().expired)
        assertEquals(HttpStatusCode.BadRequest, client.post("$keys/${rotated.id}/rotate", admin).status)

        // Audit: date range and CSV export.
        val audit = "/v1/orgs/${org.id}/audit"
        assertTrue(client.get("$audit?from=2001-01-01&to=2001-01-02") { bearerAuth(admin) }.body<List<AuditEntryDto>>().isEmpty())
        assertTrue(client.get("$audit?from=2001-01-01") { bearerAuth(admin) }.body<List<AuditEntryDto>>().any { it.action == "api_key.rotated" })
        assertEquals(HttpStatusCode.BadRequest, client.get("$audit?from=2026-12-01&to=2026-01-01") { bearerAuth(admin) }.status)
        assertEquals(HttpStatusCode.BadRequest, client.get("$audit?from=yesterday") { bearerAuth(admin) }.status)
        val export = client.get("$audit/export") { bearerAuth(admin) }
        assertEquals(HttpStatusCode.OK, export.status)
        assertTrue(export.headers[HttpHeaders.ContentType]!!.startsWith("text/csv"))
        assertTrue(export.headers[HttpHeaders.ContentDisposition]!!.contains("audit-log.csv"))
        val csv = export.bodyAsText().lines()
        assertEquals("id,at,action,actor_email,actor_user_id,actor_api_key_id,target_type,target_id,details", csv.first())
        assertTrue(csv.any { ",api_key.rotated," in it })
        // Exporting is itself audited.
        assertTrue(client.get(audit) { bearerAuth(admin) }.body<List<AuditEntryDto>>().first().action == "audit.exported")

        // Usage and the active-key limit (expired keys don't count).
        val usage: OrgUsageDto = client.get("/v1/orgs/${org.id}/usage") { bearerAuth(admin) }.body()
        assertEquals(1, usage.members)
        assertEquals(0, usage.activeApiKeys)
        assertEquals(25, usage.maxApiKeys)
        repeat(25) { i -> assertEquals(HttpStatusCode.Created, client.post(keys, admin, CreateApiKeyRequest("K$i", listOf("flows:read"))).status) }
        assertEquals(HttpStatusCode.BadRequest, client.post(keys, admin, CreateApiKeyRequest("One too many", listOf("flows:read"))).status)
        assertEquals(25, client.get("/v1/orgs/${org.id}/usage") { bearerAuth(admin) }.body<OrgUsageDto>().activeApiKeys)
        // Non-members see nothing.
        val stranger = client.registerUser().accessToken
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/orgs/${org.id}/usage") { bearerAuth(stranger) }.status)
    }
}
