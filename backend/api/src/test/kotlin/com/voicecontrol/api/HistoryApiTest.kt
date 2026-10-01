package com.voicecontrol.api

import com.voicecontrol.api.routes.Page
import com.voicecontrol.api.routes.RunDto
import com.voicecontrol.api.routes.RunStatsDto
import com.voicecontrol.api.routes.UploadRunsRequest
import com.voicecontrol.api.routes.UploadRunsResponse
import com.voicecontrol.domain.ai.ElementKind
import com.voicecontrol.domain.ai.FieldType
import com.voicecontrol.domain.ai.Language
import com.voicecontrol.domain.history.RunStatus
import com.voicecontrol.domain.history.ScreenRecord
import com.voicecontrol.domain.history.StepOutcome
import com.voicecontrol.domain.history.StepRecord
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HistoryApiTest {
    private fun run(app: String, status: RunStatus = RunStatus.COMPLETED) = RunDto(
        sessionId = UUID.randomUUID().toString(),
        appPackage = app,
        startedAtMillis = 1_700_000_000_000,
        endedAtMillis = 1_700_000_060_000,
        status = status,
        language = Language.HINGLISH,
        screens = listOf(
            ScreenRecord(
                app, "SignupActivity", "Sign up", "sig", null, null,
                listOf(
                    StepRecord("vid:name", "Name", ElementKind.TEXT_FIELD, FieldType.NAME, "Please say Name.", StepOutcome.FILLED, "rules"),
                    StepRecord("vid:pwd", "Password", ElementKind.TEXT_FIELD, FieldType.PASSWORD, null, StepOutcome.MANUAL),
                    StepRecord("vid:go", "Sign up", ElementKind.BUTTON, null, null, StepOutcome.CLICKED),
                ),
            ),
        ),
    )

    @Test
    fun `upload is idempotent, listed newest first, with stats and deletion`() = testApplication {
        application { voiceControl(TestEnvironment.services) }
        val client = jsonClient()
        val token = client.registerUser().accessToken
        val runs = listOf(run("com.shop"), run("com.bank", RunStatus.STOPPED), run("com.shop"))

        val first: UploadRunsResponse = client.post("/v1/runs") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(UploadRunsRequest(runs)) }.body()
        assertEquals(3, first.inserted)
        val again: UploadRunsResponse = client.post("/v1/runs") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(UploadRunsRequest(runs)) }.body()
        assertEquals(0, again.inserted)

        val page: Page<RunDto> = client.get("/v1/runs?appPackage=com.shop") { bearerAuth(token) }.body()
        assertEquals(2, page.items.size)
        assertEquals(1, page.items.first().filledCount)

        val stats: RunStatsDto = client.get("/v1/runs/stats") { bearerAuth(token) }.body()
        assertEquals(3, stats.totalRuns)
        assertEquals(2, stats.completedRuns)
        assertEquals("com.shop", stats.topApps.first().appPackage)

        val one = client.get("/v1/runs/${runs[1].sessionId}") { bearerAuth(token) }
        assertTrue(one.bodyAsText().contains("\"STOPPED\""))
        assertEquals(HttpStatusCode.NoContent, client.delete("/v1/runs/${runs[1].sessionId}") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/runs/${runs[1].sessionId}") { bearerAuth(token) }.status)

        // Another user cannot see them; clearing removes everything.
        val other = client.registerUser().accessToken
        assertEquals(0, client.get("/v1/runs") { bearerAuth(other) }.body<Page<RunDto>>().items.size)
        client.delete("/v1/runs") { bearerAuth(token) }
        assertEquals(0, client.get("/v1/runs") { bearerAuth(token) }.body<Page<RunDto>>().items.size)
    }

    @Test
    fun `vision with the offline provider explains it is unsupported`() = testApplication {
        application { voiceControl(TestEnvironment.services) }
        val client = jsonClient()
        val token = client.registerUser().accessToken
        val response = client.post("/v1/ai/vision") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody(com.voicecontrol.api.routes.VisionRequest("com.game", "AAEC", 10, 10))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
        assertTrue(response.bodyAsText().contains("does not support vision"))
    }
}
