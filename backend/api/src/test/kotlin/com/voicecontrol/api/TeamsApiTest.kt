package com.voicecontrol.api

import com.sun.net.httpserver.HttpServer
import com.voicecontrol.api.routes.ApiKeyDto
import com.voicecontrol.api.routes.AuditEntryDto
import com.voicecontrol.api.routes.CreateApiKeyRequest
import com.voicecontrol.api.routes.CreateFlowRequest
import com.voicecontrol.api.routes.CreateOrgRequest
import com.voicecontrol.api.routes.DeliveryDto
import com.voicecontrol.api.routes.FlowDto
import com.voicecontrol.api.routes.FlowSummaryDto
import com.voicecontrol.api.routes.InvitationDto
import com.voicecontrol.api.routes.InvitationPreviewDto
import com.voicecontrol.api.routes.InviteRequest
import com.voicecontrol.api.routes.MemberDto
import com.voicecontrol.api.routes.OrgDto
import com.voicecontrol.api.routes.Page
import com.voicecontrol.api.routes.SetRoleRequest
import com.voicecontrol.api.routes.TransferFlowRequest
import com.voicecontrol.api.routes.UpdateFlowRequest
import com.voicecontrol.api.routes.WebhookDto
import com.voicecontrol.api.routes.WebhookRequest
import com.voicecontrol.application.org.WebhookSignature
import com.voicecontrol.domain.ai.ElementKind
import com.voicecontrol.domain.ai.FieldType
import com.voicecontrol.domain.flow.FlowStep
import com.voicecontrol.domain.flow.StepAction
import com.voicecontrol.domain.org.Role
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import java.net.InetSocketAddress
import java.time.Instant
import java.util.Collections
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TeamsApiTest {
    private val unique = UUID.randomUUID().toString().take(8)
    private val app = "com.team$unique.app"
    private val steps = listOf(
        FlowStep("s1", 0, "vid:name", "Customer name", ElementKind.TEXT_FIELD, FieldType.NAME),
        FlowStep("s2", 1, "vid:go", "Save", ElementKind.BUTTON, action = StepAction.CLICK),
    )

    private suspend fun HttpClient.send(method: String, path: String, token: String, body: Any? = null, org: String? = null): HttpResponse {
        val block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            bearerAuth(token)
            org?.let { header("X-Org-Id", it) }
            if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
        }
        return when (method) {
            "GET" -> get(path, block)
            "POST" -> post(path, block)
            "PUT" -> put(path, block)
            "PATCH" -> patch(path, block)
            else -> delete(path, block)
        }
    }

    @Test
    fun `organizations, roles, invitations, org flows, transfer and audit`() = testApplication {
        application { voiceControl(TestEnvironment.services) }
        val client = jsonClient()
        val admin = client.registerUser()
        val bobEmail = "bob-$unique@example.com"
        val bob = client.registerUser(bobEmail)
        val outsider = client.registerUser()

        val org: OrgDto = client.send("POST", "/v1/orgs", admin.accessToken, CreateOrgRequest("Acme $unique")).body()
        assertEquals(Role.ADMIN, org.role)

        // Invite Bob as a viewer; only Bob's account can accept.
        val invite: InvitationDto = client.send("POST", "/v1/orgs/${org.id}/invitations", admin.accessToken, InviteRequest(bobEmail, Role.VIEWER)).body()
        val token = assertNotNull(invite.token)
        val preview: InvitationPreviewDto = client.send("GET", "/v1/invitations/$token", bob.accessToken).body()
        assertEquals("Acme $unique", preview.orgName)
        assertEquals(HttpStatusCode.Forbidden, client.send("POST", "/v1/invitations/$token/accept", outsider.accessToken).status)
        val joined: OrgDto = client.send("POST", "/v1/invitations/$token/accept", bob.accessToken).body()
        assertEquals(Role.VIEWER, joined.role)
        assertEquals(HttpStatusCode.NotFound, client.send("POST", "/v1/invitations/$token/accept", bob.accessToken).status)
        assertEquals(2, client.send("GET", "/v1/orgs/${org.id}/members", bob.accessToken).body<List<MemberDto>>().size)

        // Outsiders can't see the organization at all; viewers can't manage it.
        assertEquals(HttpStatusCode.NotFound, client.send("GET", "/v1/orgs/${org.id}/members", outsider.accessToken).status)
        assertEquals(HttpStatusCode.Forbidden, client.send("POST", "/v1/orgs/${org.id}/invitations", bob.accessToken, InviteRequest("x-$unique@example.com")).status)

        // The admin moves a personal flow into the organization.
        val flow: FlowDto = client.send("POST", "/v1/flows", admin.accessToken, CreateFlowRequest(app, "CRM entry", "sig-$unique", steps)).body()
        val moved: FlowDto = client.send("POST", "/v1/flows/${flow.id}/transfer", admin.accessToken, TransferFlowRequest(org.id)).body()
        assertEquals(org.id, moved.orgId)
        assertTrue(client.send("GET", "/v1/flows", admin.accessToken).body<Page<FlowSummaryDto>>().items.none { it.id == flow.id })
        val orgFlows: Page<FlowSummaryDto> = client.send("GET", "/v1/flows", bob.accessToken, org = org.id).body()
        assertEquals(listOf(flow.id), orgFlows.items.map { it.id })
        assertEquals(HttpStatusCode.NotFound, client.send("GET", "/v1/flows", outsider.accessToken, org = org.id).status)
        assertEquals(HttpStatusCode.NotFound, client.send("GET", "/v1/flows/${flow.id}", outsider.accessToken).status)

        // Viewers read but can't edit; editors can.
        val edit = UpdateFlowRequest(moved.version, "CRM entry v2", steps, "Renamed")
        assertEquals(HttpStatusCode.Forbidden, client.send("PUT", "/v1/flows/${flow.id}", bob.accessToken, edit).status)
        assertEquals(HttpStatusCode.Forbidden, client.send("DELETE", "/v1/flows/${flow.id}", bob.accessToken).status)
        val bobId = bob.user.id
        assertEquals(HttpStatusCode.NoContent, client.send("PATCH", "/v1/orgs/${org.id}/members/$bobId", admin.accessToken, SetRoleRequest(Role.EDITOR)).status)
        val edited: FlowDto = client.send("PUT", "/v1/flows/${flow.id}", bob.accessToken, edit).body()
        assertEquals("CRM entry v2", edited.name)

        // Editors can't move flows out; the last admin can't be demoted.
        assertEquals(HttpStatusCode.Forbidden, client.send("POST", "/v1/flows/${flow.id}/transfer", bob.accessToken, TransferFlowRequest(null)).status)
        assertEquals(
            HttpStatusCode.BadRequest,
            client.send("PATCH", "/v1/orgs/${org.id}/members/${admin.user.id}", admin.accessToken, SetRoleRequest(Role.EDITOR)).status,
        )

        // Screen matching on Bob's phone finds the organization's flow.
        val match = client.send("POST", "/v1/flows/match", bob.accessToken, mapOf("appPackage" to app, "signature" to "sig-$unique"))
        assertTrue(match.body<String>().contains(flow.id))

        // Audit log (admins only), newest first.
        assertEquals(HttpStatusCode.Forbidden, client.send("GET", "/v1/orgs/${org.id}/audit", bob.accessToken).status)
        val audit: List<AuditEntryDto> = client.send("GET", "/v1/orgs/${org.id}/audit", admin.accessToken).body()
        val actions = audit.map { it.action }
        assertTrue(listOf("flow.updated", "member.role_changed", "flow.moved_in", "member.joined", "member.invited", "org.created").all { it in actions }, actions.toString())
        assertEquals(bobEmail, audit.first { it.action == "flow.updated" }.actorEmail)
        val memberOnly: List<AuditEntryDto> = client.send("GET", "/v1/orgs/${org.id}/audit?action=member", admin.accessToken).body()
        assertTrue(memberOnly.all { it.action.startsWith("member.") } && memberOnly.isNotEmpty())

        // Bob leaves; the flow disappears for him.
        assertEquals(HttpStatusCode.NoContent, client.send("DELETE", "/v1/orgs/${org.id}/members/$bobId", bob.accessToken).status)
        assertEquals(HttpStatusCode.NotFound, client.send("GET", "/v1/flows/${flow.id}", bob.accessToken).status)
    }

    @Test
    fun `api keys are org-scoped, scope-checked, rate limited and revocable`() = testApplication {
        application { voiceControl(TestEnvironment.services) }
        val client = jsonClient()
        val admin = client.registerUser()
        val org: OrgDto = client.send("POST", "/v1/orgs", admin.accessToken, CreateOrgRequest("Keys $unique")).body()
        val personal: FlowDto = client.send("POST", "/v1/flows", admin.accessToken, CreateFlowRequest(app, "Personal", "p-$unique", steps)).body()
        val shared: FlowDto = client.send("POST", "/v1/flows", admin.accessToken, CreateFlowRequest(app, "Shared", "s-$unique", steps)).body()
        client.send("POST", "/v1/flows/${shared.id}/transfer", admin.accessToken, TransferFlowRequest(org.id))

        val readKey: ApiKeyDto = client.send("POST", "/v1/orgs/${org.id}/api-keys", admin.accessToken, CreateApiKeyRequest("Reader", listOf("flows:read"), 3)).body()
        val secret = assertNotNull(readKey.secret)
        assertTrue(secret.startsWith(readKey.prefix + "_"))
        val listed: List<ApiKeyDto> = client.send("GET", "/v1/orgs/${org.id}/api-keys", admin.accessToken).body()
        assertNull(listed.single().secret)

        suspend fun withKey(method: String, path: String, key: String, body: Any? = null) = when (method) {
            "GET" -> client.get(path) { header("X-Api-Key", key) }
            else -> client.put(path) { header("X-Api-Key", key); contentType(ContentType.Application.Json); setBody(body!!) }
        }

        // Only the organization's flows are visible.
        val page: Page<FlowSummaryDto> = withKey("GET", "/v1/flows", secret).body()
        assertEquals(listOf(shared.id), page.items.map { it.id })
        assertEquals(HttpStatusCode.NotFound, withKey("GET", "/v1/flows/${personal.id}", secret).status)
        // Missing scope.
        assertEquals(HttpStatusCode.Forbidden, withKey("PUT", "/v1/flows/${shared.id}", secret, UpdateFlowRequest(shared.version, null, steps, null)).status)
        // Limit is 3 per minute: further calls within the window are rejected.
        val statuses = (1..4).map { withKey("GET", "/v1/flows/${shared.id}", secret).status }
        assertTrue(HttpStatusCode.TooManyRequests in statuses, statuses.toString())
        // Bearer form works for a fresh key; endpoints for people reject keys.
        val writeKey: ApiKeyDto = client.send(
            "POST", "/v1/orgs/${org.id}/api-keys", admin.accessToken, CreateApiKeyRequest("Writer", listOf("flows:read", "flows:write"), 100),
        ).body()
        val updated: FlowDto = client.put("/v1/flows/${shared.id}") {
            bearerAuth(writeKey.secret!!); contentType(ContentType.Application.Json); setBody(UpdateFlowRequest(shared.version, "Shared v2", steps, "via API"))
        }.body()
        assertEquals("Shared v2", updated.name)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/orgs") { bearerAuth(writeKey.secret!!) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/v1/flows/${shared.id}/transfer") { header("X-Api-Key", writeKey.secret!!); contentType(ContentType.Application.Json); setBody(TransferFlowRequest(null)) }.status)
        val audit: List<AuditEntryDto> = client.send("GET", "/v1/orgs/${org.id}/audit", admin.accessToken).body()
        assertEquals(writeKey.id, audit.first { it.action == "flow.updated" }.actorApiKeyId)

        // Revoked keys stop working.
        assertEquals(HttpStatusCode.NoContent, client.send("DELETE", "/v1/orgs/${org.id}/api-keys/${writeKey.id}", admin.accessToken).status)
        assertEquals(HttpStatusCode.Unauthorized, withKey("GET", "/v1/flows", writeKey.secret!!).status)
        assertEquals(HttpStatusCode.Unauthorized, withKey("GET", "/v1/flows", "vck_nope_nope").status)
    }

    @Test
    fun `webhooks receive signed flow events with retries and redelivery`() = testApplication {
        application { voiceControl(TestEnvironment.services) }
        val received = Collections.synchronizedList(mutableListOf<Triple<String, Map<String, String>, String>>())
        var failNext = 1
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/hook") { ex ->
                val body = ex.requestBody.readAllBytes().decodeToString()
                val headers = ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.first() }
                val status = synchronized(this@TeamsApiTest) { if (failNext > 0) { failNext--; 500 } else 204 }
                if (status == 204) received += Triple(ex.requestURI.path, headers, body)
                ex.sendResponseHeaders(status, -1)
                ex.close()
            }
            start()
        }
        try {
            val client = jsonClient()
            val admin = client.registerUser()
            val org: OrgDto = client.send("POST", "/v1/orgs", admin.accessToken, CreateOrgRequest("Hooks $unique")).body()
            val url = "http://127.0.0.1:${server.address.port}/hook"
            assertEquals(
                HttpStatusCode.BadRequest,
                client.send("POST", "/v1/orgs/${org.id}/webhooks", admin.accessToken, WebhookRequest(url, listOf("flow.nonsense"))).status,
            )
            val hook: WebhookDto = client.send("POST", "/v1/orgs/${org.id}/webhooks", admin.accessToken, WebhookRequest(url, listOf("flow.version_saved"))).body()
            val secret = assertNotNull(hook.secret)

            val flow: FlowDto = client.send("POST", "/v1/flows", admin.accessToken, CreateFlowRequest(app, "Hooked", "h-$unique", steps)).body()
            client.send("POST", "/v1/flows/${flow.id}/transfer", admin.accessToken, TransferFlowRequest(org.id))

            // The event bus queues the delivery asynchronously.
            var deliveries: List<DeliveryDto> = emptyList()
            repeat(50) {
                deliveries = client.send("GET", "/v1/orgs/${org.id}/webhooks/${hook.id}/deliveries", admin.accessToken).body()
                if (deliveries.isNotEmpty()) return@repeat
                delay(100)
            }
            val delivery = deliveries.single()
            assertEquals("flow.version_saved", delivery.eventType)

            // First attempt fails (500) and is rescheduled with backoff; the next due attempt succeeds.
            val worker = TestEnvironment.services.webhookWorker
            worker.tick()
            var failed = client.send("GET", "/v1/orgs/${org.id}/webhooks/${hook.id}/deliveries", admin.accessToken).body<List<DeliveryDto>>().single()
            assertEquals("PENDING", failed.status)
            assertEquals(1, failed.attempts)
            assertEquals(500, failed.lastStatusCode)
            assertEquals(0, worker.tick(Instant.now()))
            worker.tick(Instant.now().plusSeconds(31))
            failed = client.send("GET", "/v1/orgs/${org.id}/webhooks/${hook.id}/deliveries", admin.accessToken).body<List<DeliveryDto>>().single()
            assertEquals("SUCCEEDED", failed.status)

            val (_, headers, body) = received.single()
            assertEquals("flow.version_saved", headers["x-voicecontrol-event"])
            assertTrue(WebhookSignature.verify(secret, headers.getValue("x-voicecontrol-signature"), body, Instant.now()))
            assertTrue(body.contains(flow.id) && body.contains(org.id))

            // Redeliver and ping.
            client.send("POST", "/v1/orgs/${org.id}/webhooks/deliveries/${delivery.id}/redeliver", admin.accessToken)
            client.send("POST", "/v1/orgs/${org.id}/webhooks/${hook.id}/ping", admin.accessToken)
            worker.tick()
            assertEquals(listOf("flow.version_saved", "ping"), received.drop(1).map { it.second["x-voicecontrol-event"] }.sortedBy { it })

            // Rotating the secret changes signatures.
            val rotated: WebhookDto = client.send("POST", "/v1/orgs/${org.id}/webhooks/${hook.id}/rotate-secret", admin.accessToken).body()
            assertTrue(rotated.secret != secret)
        } finally {
            server.stop(0)
        }
    }
}
