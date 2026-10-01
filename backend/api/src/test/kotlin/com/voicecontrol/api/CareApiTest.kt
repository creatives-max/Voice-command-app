package com.voicecontrol.api

import com.voicecontrol.api.routes.CareAcceptRequest
import com.voicecontrol.api.routes.CareEventDto
import com.voicecontrol.api.routes.CareInviteDto
import com.voicecontrol.api.routes.CareInviteRequest
import com.voicecontrol.api.routes.CareLinkDto
import com.voicecontrol.api.routes.CarePermissionsRequest
import com.voicecontrol.api.routes.CreateFlowRequest
import com.voicecontrol.api.routes.FlowDto
import com.voicecontrol.api.routes.FlowVersionDto
import com.voicecontrol.api.routes.Page
import com.voicecontrol.api.routes.FlowSummaryDto
import com.voicecontrol.api.routes.RunNowRequest
import com.voicecontrol.api.routes.UpdateFlowRequest
import com.voicecontrol.domain.ai.ElementKind
import com.voicecontrol.domain.ai.FieldType
import com.voicecontrol.domain.flow.FlowStep
import com.voicecontrol.domain.flow.VersionSource
import com.voicecontrol.domain.user.Profile
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CareApiTest {
    private val unique = UUID.randomUUID().toString().take(8)
    private val steps = listOf(FlowStep("s1", 0, "vid:name", "Full name", ElementKind.TEXT_FIELD, FieldType.NAME))

    private suspend fun HttpClient.send(method: String, path: String, token: String, body: Any? = null, link: String? = null): HttpResponse {
        val block: HttpRequestBuilder.() -> Unit = {
            bearerAuth(token)
            link?.let { header("X-Care-Link", it) }
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
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
    fun `a caregiver helps with consent, within the granted permissions, and can be removed`() = testApplication {
        application { voiceControl(TestEnvironment.services) }
        val client = jsonClient()
        val mom = client.registerUser("mom-$unique@example.com").accessToken
        val son = client.registerUser("son-$unique@example.com").accessToken
        val stranger = client.registerUser().accessToken
        val momsFlow: FlowDto = client.send("POST", "/v1/flows", mom, CreateFlowRequest("com.bank$unique.app", "Pay bill", "sig-$unique", steps)).body()
        val sonsFlow: FlowDto = client.send("POST", "/v1/flows", son, CreateFlowRequest("com.shop$unique.app", "Son's own", "sig2-$unique", steps)).body()

        // Mom invites her son and allows editing only.
        val invite: CareInviteDto = client.send("POST", "/v1/care/invites", mom, CareInviteRequest(listOf("edit_flows"))).body()
        assertTrue(Regex("^[A-Z2-9]{4}-[A-Z2-9]{4}$").matches(invite.code))
        assertEquals("PENDING", invite.link.status)
        assertEquals(HttpStatusCode.BadRequest, client.send("POST", "/v1/care/invites", mom, CareInviteRequest(listOf("everything"))).status)
        // Nobody can use their own code; a wrong code is refused.
        assertEquals(HttpStatusCode.BadRequest, client.send("POST", "/v1/care/accept", mom, CareAcceptRequest(invite.code)).status)
        assertEquals(HttpStatusCode.NotFound, client.send("POST", "/v1/care/accept", son, CareAcceptRequest("AAAA-AAAA")).status)

        val link: CareLinkDto = client.send("POST", "/v1/care/accept", son, CareAcceptRequest(invite.code.lowercase().replace("-", " "))).body()
        assertEquals("caregiver", link.role)
        assertEquals("ACTIVE", link.status)
        assertEquals("mom-$unique@example.com", link.otherEmail)
        assertEquals(HttpStatusCode.NotFound, client.send("POST", "/v1/care/accept", stranger, CareAcceptRequest(invite.code)).status)
        val momSees: List<CareLinkDto> = client.send("GET", "/v1/care/links", mom).body()
        assertEquals("son-$unique@example.com", momSees.single().otherEmail)
        assertEquals("receiver", momSees.single().role)

        // Acting for mom: her flows, not his.
        val hers: Page<FlowSummaryDto> = client.send("GET", "/v1/flows", son, link = link.id).body()
        assertEquals(listOf(momsFlow.id), hers.items.map { it.id })
        val his: Page<FlowSummaryDto> = client.send("GET", "/v1/flows", son).body()
        assertEquals(listOf(sonsFlow.id), his.items.map { it.id })
        assertEquals(HttpStatusCode.NotFound, client.send("GET", "/v1/flows/${momsFlow.id}", son).status)

        val edited: FlowDto = client.send(
            "PUT", "/v1/flows/${momsFlow.id}", son,
            UpdateFlowRequest(1, steps = steps.map { it.copy(question = "Aapka poora naam?") }, changeNote = "Hindi question"), link = link.id,
        ).body()
        assertEquals(2, edited.version)
        val versions: List<FlowVersionDto> = client.send("GET", "/v1/flows/${momsFlow.id}/versions", mom).body()
        assertEquals(VersionSource.CAREGIVER, versions.first().source)
        assertEquals("By Test User: Hindi question", versions.first().changeNote)

        // Not granted: running flows and history. Never allowed: deleting, profile, account.
        client.send("POST", "/v1/devices", mom, mapOf("id" to UUID.randomUUID().toString(), "name" to "Mom's phone"))
        assertEquals(HttpStatusCode.Forbidden, client.send("POST", "/v1/run-requests", son, RunNowRequest(momsFlow.id), link = link.id).status)
        assertEquals(HttpStatusCode.Forbidden, client.send("GET", "/v1/runs", son, link = link.id).status)
        assertEquals(HttpStatusCode.Forbidden, client.send("DELETE", "/v1/flows/${momsFlow.id}", son, link = link.id).status)
        assertEquals(HttpStatusCode.Forbidden, client.send("PUT", "/v1/profile", son, Profile(fullName = "Hacked"), link = link.id).status)
        assertEquals(HttpStatusCode.Forbidden, client.send("DELETE", "/v1/me", son, mapOf("password" to "secret123"), link = link.id).status)
        // Other GETs answer for the caregiver themself.
        assertNull(client.send("GET", "/v1/profile", son, link = link.id).body<Profile>().fullName)
        // A stranger can't use the link.
        assertEquals(HttpStatusCode.Forbidden, client.send("GET", "/v1/flows", stranger, link = link.id).status)

        // Mom allows running flows; only she can change permissions.
        assertEquals(HttpStatusCode.Forbidden, client.send("PATCH", "/v1/care/links/${link.id}", son, CarePermissionsRequest(listOf("run_flows"))).status)
        val widened: CareLinkDto = client.send("PATCH", "/v1/care/links/${link.id}", mom, CarePermissionsRequest(listOf("edit_flows", "run_flows"))).body()
        assertEquals(listOf("edit_flows", "run_flows"), widened.permissions)
        assertEquals(HttpStatusCode.Created, client.send("POST", "/v1/run-requests", son, RunNowRequest(momsFlow.id), link = link.id).status)

        // Both see what happened.
        val events: List<CareEventDto> = client.send("GET", "/v1/care/links/${link.id}/events", mom).body()
        val actions = events.map { it.action }
        assertEquals(listOf("flow_run", "permissions_changed", "flow_edited", "accepted", "invited"), actions)
        assertEquals("Pay bill", events.first { it.action == "flow_edited" }.details["flowName"])
        assertEquals("son-$unique@example.com", events.first { it.action == "flow_edited" }.actorEmail)
        assertEquals(HttpStatusCode.NotFound, client.send("GET", "/v1/care/links/${link.id}/events", stranger).status)

        // Mom ends it: the son loses access at once.
        assertEquals(HttpStatusCode.NoContent, client.send("DELETE", "/v1/care/links/${link.id}", mom).status)
        assertEquals(HttpStatusCode.Forbidden, client.send("GET", "/v1/flows", son, link = link.id).status)
        assertTrue(client.send("GET", "/v1/care/links", son).body<List<CareLinkDto>>().isEmpty())

        // The history of the link is part of both people's data export.
        val export = kotlinx.serialization.json.Json.parseToJsonElement(client.send("GET", "/v1/me/export", mom).body<String>()).jsonObject
        val caregiving = export["caregiving"]!!.jsonArray.single().jsonObject
        assertEquals("helped by", caregiving["relation"]!!.jsonPrimitive.content)
        assertEquals("REVOKED", caregiving["status"]!!.jsonPrimitive.content)
        assertEquals(6, caregiving["events"]!!.jsonArray.size)
    }

    @Test
    fun `pending invites can be cancelled and are limited`() = testApplication {
        application { voiceControl(TestEnvironment.services) }
        val client = jsonClient()
        val mom = client.registerUser().accessToken
        val son = client.registerUser().accessToken
        val invites = (1..5).map { client.send("POST", "/v1/care/invites", mom, CareInviteRequest()).body<CareInviteDto>() }
        assertEquals(HttpStatusCode.BadRequest, client.send("POST", "/v1/care/invites", mom, CareInviteRequest()).status)
        assertEquals(5, client.send("GET", "/v1/care/links", mom).body<List<CareLinkDto>>().size)
        assertEquals(HttpStatusCode.NoContent, client.send("DELETE", "/v1/care/links/${invites[0].link.id}", mom).status)
        assertEquals(HttpStatusCode.NotFound, client.send("POST", "/v1/care/accept", son, CareAcceptRequest(invites[0].code)).status)
        // An invite without permissions still lets the caregiver see (not change) flows.
        val link: CareLinkDto = client.send("POST", "/v1/care/accept", son, CareAcceptRequest(invites[1].code)).body()
        assertTrue(link.permissions.isEmpty())
        assertEquals(HttpStatusCode.OK, client.send("GET", "/v1/devices", son, link = link.id).status)
    }
}
