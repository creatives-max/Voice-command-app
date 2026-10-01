package com.voicecontrol.api

import com.voicecontrol.api.routes.ApiKeyDto
import com.voicecontrol.api.routes.CreateApiKeyRequest
import com.voicecontrol.api.routes.CreateFlowRequest
import com.voicecontrol.api.routes.CreateOrgRequest
import com.voicecontrol.api.routes.DryRunRequest
import com.voicecontrol.api.routes.DryRunResponse
import com.voicecontrol.api.routes.FlowDto
import com.voicecontrol.api.routes.OrgDto
import com.voicecontrol.api.routes.TransferFlowRequest
import com.voicecontrol.domain.ai.ElementKind
import com.voicecontrol.domain.ai.FieldType
import com.voicecontrol.domain.flow.FlowStep
import com.voicecontrol.domain.flow.ProfileKey
import com.voicecontrol.domain.flow.StepAction
import com.voicecontrol.domain.user.Profile
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DryRunApiTest {
    private val unique = UUID.randomUUID().toString().take(8)
    private val steps = listOf(
        FlowStep("name", 0, "vid:name", "Full name", ElementKind.TEXT_FIELD, FieldType.NAME, profileKey = ProfileKey.FULL_NAME),
        FlowStep("married", 1, "vid:m", "Married", ElementKind.CHECKBOX, action = StepAction.TOGGLE, variable = "married"),
        FlowStep("spouse", 2, "vid:s", "Spouse name", ElementKind.TEXT_FIELD, FieldType.NAME, condition = "yes(married)", elseValue = "'N/A'"),
        FlowStep("go", 3, "vid:go", "Submit", ElementKind.BUTTON, action = StepAction.CLICK),
    )

    @Test
    fun `dry run walks a saved flow with scripted answers and the caller's profile`() = testApplication {
        application { voiceControl(TestEnvironment.services) }
        val client = jsonClient()
        val token = client.registerUser().accessToken
        client.put("/v1/profile") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(Profile(fullName = "Asha Rao")) }
        val flow: FlowDto = client.post("/v1/flows") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody(CreateFlowRequest("com.dry$unique.app", "Dry", "sig-$unique", steps))
        }.body()

        suspend fun dryRun(body: DryRunRequest) = client.post("/v1/flows/${flow.id}/dry-run") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody(body)
        }

        val first: DryRunResponse = dryRun(DryRunRequest(today = "2026-10-01")).body()
        assertFalse(first.finished)
        assertEquals(1, first.flowVersion)
        val pending = assertNotNull(first.pending)
        assertEquals("name", pending.stepId)
        assertEquals("Please say Full name. Say yes to use Asha Rao.", pending.question)
        assertEquals("text", pending.expects)

        val done: DryRunResponse = dryRun(DryRunRequest(listOf("yes", "no", "haan"))).body()
        assertTrue(done.finished)
        assertEquals(mapOf("name" to "Asha Rao", "married" to "no", "spouse" to "N/A"), done.values)
        assertEquals("press", done.transcript.dropLast(1).last().kind)

        // Unsaved edits: without the profile the suggestion is gone.
        val edited: DryRunResponse = dryRun(DryRunRequest(useProfile = false, steps = steps.drop(1))).body()
        assertEquals("Should I select \"Married\"? Say yes or no.", edited.pending?.question)
        assertEquals("yesno", edited.pending?.expects)

        val invalid = dryRun(DryRunRequest(steps = listOf(steps[0], steps[0])))
        assertEquals(HttpStatusCode.BadRequest, invalid.status)
    }

    @Test
    fun `api keys with flows read can dry run org flows but never see a profile`() = testApplication {
        application { voiceControl(TestEnvironment.services) }
        val client = jsonClient()
        val token = client.registerUser().accessToken
        client.put("/v1/profile") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(Profile(fullName = "Asha Rao")) }
        val org: OrgDto = client.post("/v1/orgs") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(CreateOrgRequest("Dry $unique")) }.body()
        val flow: FlowDto = client.post("/v1/flows") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody(CreateFlowRequest("com.dry$unique.app", "Dry", "sig-$unique", steps))
        }.body()
        client.post("/v1/flows/${flow.id}/transfer") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(TransferFlowRequest(org.id)) }
        val key: ApiKeyDto = client.post("/v1/orgs/${org.id}/api-keys") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody(CreateApiKeyRequest("Reader", listOf("flows:read")))
        }.body()

        val r: DryRunResponse = client.post("/v1/flows/${flow.id}/dry-run") {
            header("X-Api-Key", key.secret!!); contentType(ContentType.Application.Json); setBody(DryRunRequest())
        }.body()
        assertEquals("Please say Full name.", r.pending?.question)
    }
}
