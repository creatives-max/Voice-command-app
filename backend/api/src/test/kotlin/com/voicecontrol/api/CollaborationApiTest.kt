package com.voicecontrol.api

import com.voicecontrol.api.routes.CommentDto
import com.voicecontrol.api.routes.CreateFlowRequest
import com.voicecontrol.api.routes.CreateOrgRequest
import com.voicecontrol.api.routes.EditCommentRequest
import com.voicecontrol.api.routes.FlowAnalyticsDto
import com.voicecontrol.api.routes.FlowDto
import com.voicecontrol.api.routes.InvitationDto
import com.voicecontrol.api.routes.InviteRequest
import com.voicecontrol.api.routes.LayoutDto
import com.voicecontrol.api.routes.NewCommentRequest
import com.voicecontrol.api.routes.OrgDto
import com.voicecontrol.api.routes.OverviewDto
import com.voicecontrol.api.routes.PositionDto
import com.voicecontrol.api.routes.PresenceDto
import com.voicecontrol.api.routes.PresenceRequest
import com.voicecontrol.api.routes.RunDto
import com.voicecontrol.api.routes.TransferFlowRequest
import com.voicecontrol.api.routes.UploadRunsRequest
import com.voicecontrol.domain.ai.ElementKind
import com.voicecontrol.domain.ai.FieldType
import com.voicecontrol.domain.ai.Language
import com.voicecontrol.domain.flow.FlowStep
import com.voicecontrol.domain.flow.StepAction
import com.voicecontrol.domain.history.RunStatus
import com.voicecontrol.domain.history.ScreenRecord
import com.voicecontrol.domain.history.StepOutcome
import com.voicecontrol.domain.history.StepRecord
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
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CollaborationApiTest {
    private val unique = UUID.randomUUID().toString().take(8)
    private val app = "com.collab$unique.app"
    private val steps = listOf(
        FlowStep("s1", 0, "vid:name", "Name", ElementKind.TEXT_FIELD, FieldType.NAME),
        FlowStep("s2", 1, "vid:pin", "Pincode", ElementKind.TEXT_FIELD, FieldType.PINCODE),
        FlowStep("s3", 2, "vid:go", "Submit", ElementKind.BUTTON, action = StepAction.CLICK),
    )

    private fun run(flowId: String?, status: RunStatus, pin: StepOutcome, ago: Long = 0) = RunDto(
        sessionId = UUID.randomUUID().toString(),
        appPackage = app,
        startedAtMillis = Instant.now().minusSeconds(ago + 90).toEpochMilli(),
        endedAtMillis = Instant.now().minusSeconds(ago + 30).toEpochMilli(),
        status = status,
        language = Language.HINDI,
        screens = listOf(
            ScreenRecord(
                app, null, "Form", "sig-$unique", flowId, 1,
                listOf(
                    StepRecord("vid:name", "Name", ElementKind.TEXT_FIELD, FieldType.NAME, null, StepOutcome.FILLED, "rules"),
                    StepRecord("vid:pin", "Pincode", ElementKind.TEXT_FIELD, FieldType.PINCODE, null, pin, if (pin == StepOutcome.FILLED) "llm" else null),
                    StepRecord("vid:go", "Submit", ElementKind.BUTTON, null, null, if (status == RunStatus.COMPLETED) StepOutcome.CLICKED else StepOutcome.SKIPPED),
                ),
            ),
        ),
    )

    private suspend fun HttpClient.json(method: String, path: String, token: String, body: Any? = null, org: String? = null) =
        when (method) {
            "POST" -> post(path) { bearerAuth(token); org?.let { header("X-Org-Id", it) }; contentType(ContentType.Application.Json); setBody(body ?: emptyMap<String, String>()) }
            "PUT" -> put(path) { bearerAuth(token); contentType(ContentType.Application.Json); setBody(body!!) }
            "PATCH" -> patch(path) { bearerAuth(token); contentType(ContentType.Application.Json); setBody(body!!) }
            "DELETE" -> delete(path) { bearerAuth(token) }
            else -> get(path) { bearerAuth(token); org?.let { header("X-Org-Id", it) } }
        }

    @Test
    fun `org flow analytics aggregate members' runs, comments, presence and layout`() = testApplication {
        application { voiceControl(TestEnvironment.services) }
        val client = jsonClient()
        val admin = client.registerUser()
        val viewerEmail = "viewer-$unique@example.com"
        val viewer = client.registerUser(viewerEmail)
        val outsider = client.registerUser()

        val org: OrgDto = client.json("POST", "/v1/orgs", admin.accessToken, CreateOrgRequest("Collab $unique")).body()
        val invite: InvitationDto = client.json("POST", "/v1/orgs/${org.id}/invitations", admin.accessToken, InviteRequest(viewerEmail, Role.VIEWER)).body()
        client.json("POST", "/v1/invitations/${invite.token}/accept", viewer.accessToken)
        val flow: FlowDto = client.json("POST", "/v1/flows", admin.accessToken, CreateFlowRequest(app, "Collab form", "sig-$unique", steps)).body()
        client.json("POST", "/v1/flows/${flow.id}/transfer", admin.accessToken, TransferFlowRequest(org.id))

        // Runs from both members; one run without the flow is ignored.
        client.json("POST", "/v1/runs", admin.accessToken, UploadRunsRequest(listOf(run(flow.id, RunStatus.COMPLETED, StepOutcome.FILLED), run(flow.id, RunStatus.STOPPED, StepOutcome.MANUAL))))
        client.json("POST", "/v1/runs", viewer.accessToken, UploadRunsRequest(listOf(run(flow.id, RunStatus.COMPLETED, StepOutcome.FILLED, ago = 86_400 * 2), run(null, RunStatus.FAILED, StepOutcome.SKIPPED))))

        val stats: FlowAnalyticsDto = client.json("GET", "/v1/flows/${flow.id}/analytics?days=7&tz=Asia/Kolkata", viewer.accessToken).body()
        assertEquals(3, stats.usage.runs)
        assertEquals(2, stats.usage.completed)
        assertEquals(2, stats.usage.users)
        assertEquals(2.0 / 3, stats.usage.successRate!!, 1e-9)
        assertEquals(60_000L, stats.usage.avgDurationMillis)
        assertEquals(7, stats.daily.size)
        assertEquals(3, stats.daily.sumOf { it.completed + it.stopped + it.failed })
        val pin = stats.steps.first { it.elementId == "vid:pin" }
        assertEquals(mapOf("FILLED" to 2, "MANUAL" to 1), pin.outcomes)
        assertEquals(mapOf("rules" to 3, "llm" to 2), stats.interpretedBy)
        assertEquals(HttpStatusCode.NotFound, client.json("GET", "/v1/flows/${flow.id}/analytics", outsider.accessToken).status)

        val overview: OverviewDto = client.json("GET", "/v1/analytics/flows?days=30", admin.accessToken, org = org.id).body()
        assertEquals(listOf(flow.id), overview.flows.map { it.flowId })
        assertEquals(3, overview.totals.runs)
        val personal: OverviewDto = client.json("GET", "/v1/analytics/flows?days=30", admin.accessToken).body()
        assertTrue(personal.flows.isEmpty())

        // Comments: viewers may comment; only authors edit; editors (not viewers) resolve others'; admins delete any.
        val c1: CommentDto = client.json("POST", "/v1/flows/${flow.id}/comments", viewer.accessToken, NewCommentRequest("Pincode is often typed by hand — add a question?", "s2")).body()
        assertEquals(viewerEmail, c1.authorEmail)
        assertEquals("s2", c1.stepId)
        assertEquals(HttpStatusCode.BadRequest, client.json("POST", "/v1/flows/${flow.id}/comments", viewer.accessToken, NewCommentRequest("x", "nope")).status)
        assertEquals(HttpStatusCode.Forbidden, client.json("PATCH", "/v1/comments/${c1.id}", admin.accessToken, EditCommentRequest(body = "hijack")).status)
        val edited: CommentDto = client.json("PATCH", "/v1/comments/${c1.id}", viewer.accessToken, EditCommentRequest(body = "Pincode is often typed by hand.")).body()
        assertNotNull(edited.editedAt)
        val c2: CommentDto = client.json("POST", "/v1/flows/${flow.id}/comments", admin.accessToken, NewCommentRequest("Added a question in v2")).body()
        assertEquals(HttpStatusCode.Forbidden, client.json("PATCH", "/v1/comments/${c2.id}", viewer.accessToken, EditCommentRequest(resolved = true)).status)
        val resolved: CommentDto = client.json("PATCH", "/v1/comments/${c1.id}", admin.accessToken, EditCommentRequest(resolved = true)).body()
        assertNotNull(resolved.resolvedAt)
        assertEquals(HttpStatusCode.Forbidden, client.json("DELETE", "/v1/comments/${c2.id}", viewer.accessToken).status)
        assertEquals(HttpStatusCode.NoContent, client.json("DELETE", "/v1/comments/${c1.id}", admin.accessToken).status)
        assertEquals(listOf(c2.id), client.json("GET", "/v1/flows/${flow.id}/comments", viewer.accessToken).body<List<CommentDto>>().map { it.id })
        assertEquals(HttpStatusCode.NotFound, client.json("GET", "/v1/flows/${flow.id}/comments", outsider.accessToken).status)

        // Presence: each sees the other.
        client.json("POST", "/v1/flows/${flow.id}/presence", admin.accessToken, PresenceRequest(editing = true))
        val seen: PresenceDto = client.json("POST", "/v1/flows/${flow.id}/presence", viewer.accessToken, PresenceRequest()).body()
        assertEquals(listOf(true), seen.others.map { it.editing })
        assertEquals(flow.version, seen.currentVersion) // moving a flow doesn't add a version
        client.json("DELETE", "/v1/flows/${flow.id}/presence", admin.accessToken)
        assertTrue(client.json("POST", "/v1/flows/${flow.id}/presence", viewer.accessToken, PresenceRequest()).body<PresenceDto>().others.isEmpty())

        // Layout: editors save, everyone reads, viewers can't change it.
        val layout = LayoutDto(mapOf("s1" to PositionDto(0.0, 0.0), "s2" to PositionDto(260.0, 40.5)))
        assertEquals(HttpStatusCode.NoContent, client.json("PUT", "/v1/flows/${flow.id}/layout", admin.accessToken, layout).status)
        assertEquals(layout, client.json("GET", "/v1/flows/${flow.id}/layout", viewer.accessToken).body<LayoutDto>())
        assertEquals(HttpStatusCode.Forbidden, client.json("PUT", "/v1/flows/${flow.id}/layout", viewer.accessToken, layout).status)
    }
}
