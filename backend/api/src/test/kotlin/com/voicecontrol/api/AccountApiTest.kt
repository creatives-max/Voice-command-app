package com.voicecontrol.api

import com.voicecontrol.api.routes.CrashGroupDto
import com.voicecontrol.api.routes.CrashReportDto
import com.voicecontrol.api.routes.CrashUploadRequest
import com.voicecontrol.api.routes.CrashUploadResponse
import com.voicecontrol.api.routes.CreateFlowRequest
import com.voicecontrol.api.routes.CreateOrgRequest
import com.voicecontrol.api.routes.DeleteAccountRequest
import com.voicecontrol.api.routes.FlowDto
import com.voicecontrol.api.routes.InvitationDto
import com.voicecontrol.api.routes.InviteRequest
import com.voicecontrol.api.routes.OrgDto
import com.voicecontrol.api.routes.TransferFlowRequest
import com.voicecontrol.application.account.CrashService
import com.voicecontrol.domain.ai.ElementKind
import com.voicecontrol.domain.ai.FieldType
import com.voicecontrol.domain.flow.FlowStep
import com.voicecontrol.domain.org.Role
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AccountApiTest {
    private val unique = UUID.randomUUID().toString().take(8)
    private val steps = listOf(FlowStep("s1", 0, "vid:name", "Name", ElementKind.TEXT_FIELD, FieldType.NAME, defaultValue = "Asha"))

    private suspend fun HttpClient.postJson(path: String, token: String?, body: Any) =
        post(path) { token?.let { bearerAuth(it) }; contentType(ContentType.Application.Json); setBody(body) }

    @Test
    fun `export contains the user's data without secrets and deletion erases it`() = testApplication {
        application { voiceControl(TestEnvironment.services) }
        val client = jsonClient()
        val email = "gdpr-$unique@example.com"
        val me = client.registerUser(email)
        val flow: FlowDto = client.postJson("/v1/flows", me.accessToken, CreateFlowRequest("com.gdpr$unique.app", "Mine", "sig-$unique", steps)).body()

        val export = client.get("/v1/me/export") { bearerAuth(me.accessToken) }
        assertTrue(export.headers[HttpHeaders.ContentDisposition]!!.contains("voicecontrol-data-"))
        val text = export.bodyAsText()
        val doc = Json.parseToJsonElement(text).jsonObject
        assertEquals(email, doc["account"]!!.jsonArray.single().jsonObject["email"]!!.jsonPrimitive.content)
        assertEquals(flow.id, doc["flows"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content)
        assertTrue(text.contains("Asha"))
        assertFalse(text.contains("password_hash") || text.contains("\$2a\$"))

        assertEquals(HttpStatusCode.Forbidden, client.delete("/v1/me") { bearerAuth(me.accessToken); contentType(ContentType.Application.Json); setBody(DeleteAccountRequest("wrong1234")) }.status)
        assertEquals(HttpStatusCode.NoContent, client.delete("/v1/me") { bearerAuth(me.accessToken); contentType(ContentType.Application.Json); setBody(DeleteAccountRequest("secret123")) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.postJson("/v1/auth/login", null, mapOf("email" to email, "password" to "secret123")).status)
        assertEquals(HttpStatusCode.Unauthorized, client.postJson("/v1/auth/refresh", null, mapOf("refreshToken" to me.refreshToken)).status)
        // The email can be used again.
        assertEquals(email, client.registerUser(email).user.email)
    }

    @Test
    fun `sole admins must hand over first and organization flows survive the deletion`() = testApplication {
        application { voiceControl(TestEnvironment.services) }
        val client = jsonClient()
        val admin = client.registerUser()
        val memberEmail = "member-$unique@example.com"
        val member = client.registerUser(memberEmail)
        val org: OrgDto = client.postJson("/v1/orgs", admin.accessToken, CreateOrgRequest("Keep $unique")).body()
        val invite: InvitationDto = client.postJson("/v1/orgs/${org.id}/invitations", admin.accessToken, InviteRequest(memberEmail, Role.EDITOR)).body()
        client.postJson("/v1/invitations/${invite.token}/accept", member.accessToken, emptyMap<String, String>())
        val flow: FlowDto = client.postJson("/v1/flows", admin.accessToken, CreateFlowRequest("com.keep$unique.app", "Team flow", "k-$unique", steps)).body()
        client.postJson("/v1/flows/${flow.id}/transfer", admin.accessToken, TransferFlowRequest(org.id))

        val blocked = client.delete("/v1/me") { bearerAuth(admin.accessToken); contentType(ContentType.Application.Json); setBody(DeleteAccountRequest("secret123")) }
        assertEquals(HttpStatusCode.Conflict, blocked.status)
        assertTrue(blocked.bodyAsText().contains("Keep $unique"))

        assertEquals(HttpStatusCode.NoContent, client.patchRole(admin.accessToken, org.id, member.user.id, Role.ADMIN))
        assertEquals(HttpStatusCode.NoContent, client.delete("/v1/me") { bearerAuth(admin.accessToken); contentType(ContentType.Application.Json); setBody(DeleteAccountRequest("secret123")) }.status)
        val still = client.get("/v1/flows/${flow.id}") { bearerAuth(member.accessToken) }
        assertEquals(HttpStatusCode.OK, still.status)
    }

    private suspend fun HttpClient.patchRole(token: String, orgId: String, userId: String, role: Role) =
        this.patch("/v1/orgs/$orgId/members/$userId") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(mapOf("role" to role.name)) }.status

    @Test
    fun `crash reports are scrubbed, grouped and listed for their user`() = testApplication {
        application { voiceControl(TestEnvironment.services) }
        val client = jsonClient()
        val me = client.registerUser()
        val stack = """
            java.lang.IllegalStateException: OTP 482913 for asha@example.com
                at com.voicecontrol.core.engine.AssistantEngine.runStep(AssistantEngine.kt:120)
                at com.voicecontrol.core.engine.AssistantEngine.run(AssistantEngine.kt:80)
                at kotlinx.coroutines.DispatchedTask.run(DispatchedTask.kt:108)
        """.trimIndent()
        val report = CrashReportDto("java.lang.IllegalStateException", "OTP 482913 for asha@example.com", stack, "main", "1.0.0", 34, "Pixel 8", System.currentTimeMillis())
        val ok: CrashUploadResponse = client.postJson("/v1/crashes", me.accessToken, CrashUploadRequest(listOf(report, report.copy(stacktrace = stack.replace("120", "121"), occurredAtMillis = report.occurredAtMillis + 1_000)))).body()
        assertEquals(2, ok.accepted)
        // Anonymous uploads are accepted too.
        assertEquals(HttpStatusCode.OK, client.postJson("/v1/crashes", null, CrashUploadRequest(listOf(report))).status)

        val groups: List<CrashGroupDto> = client.get("/v1/crashes") { bearerAuth(me.accessToken) }.body()
        val g = groups.single()
        assertEquals(2, g.count)
        assertEquals("<digits>", Regex("OTP (\\S+)").find(g.message!!)!!.groupValues[1])
        assertFalse(g.latestStacktrace.contains("asha@example.com"))
        assertEquals("com.voicecontrol.core.engine.AssistantEngine.runStep(AssistantEngine.kt:121)", g.topFrame)
    }

    @Test
    fun `fingerprints ignore line numbers and prefer app frames`() {
        val a = CrashService.fingerprint("E", "at x.Y.z(Y.kt:1)\nat com.voicecontrol.A.b(A.kt:3)")
        val b = CrashService.fingerprint("E", "at x.Y.z(Y.kt:9)\nat com.voicecontrol.A.b(A.kt:4)")
        assertEquals(a, b)
        assertNotEquals(a, CrashService.fingerprint("F", "at com.voicecontrol.A.b(A.kt:3)"))
        assertEquals("call <digits> or <email>", CrashService.scrub("call 9876543210 or a.b@c.io"))
    }
}
