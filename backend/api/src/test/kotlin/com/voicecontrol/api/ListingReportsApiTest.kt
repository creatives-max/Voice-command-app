package com.voicecontrol.api

import com.voicecontrol.api.routes.CreateFlowRequest
import com.voicecontrol.api.routes.FlowDto
import com.voicecontrol.api.routes.ImportRequest
import com.voicecontrol.api.routes.ListingDetailDto
import com.voicecontrol.api.routes.ListingDto
import com.voicecontrol.api.routes.Page
import com.voicecontrol.api.routes.PublishRequest
import com.voicecontrol.api.routes.ReportRequest
import com.voicecontrol.api.routes.ReportResponse
import com.voicecontrol.domain.ai.ElementKind
import com.voicecontrol.domain.ai.FieldType
import com.voicecontrol.domain.flow.FlowStep
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ListingReportsApiTest {
    private val unique = UUID.randomUUID().toString().take(8)

    private suspend fun HttpClient.post(path: String, token: String, body: Any): HttpResponse = post(path) {
        bearerAuth(token); contentType(ContentType.Application.Json); setBody(body)
    }

    private suspend fun HttpClient.search(token: String, extra: String = ""): List<ListingDto> =
        get("/v1/marketplace?q=spammy$unique$extra") { bearerAuth(token) }.body<Page<ListingDto>>().items

    @Test
    fun `listings reported by three people are hidden until a fixed version is published`() = testApplication {
        application { voiceControl(TestEnvironment.services) }
        val client = jsonClient()
        val owner = client.registerUser().accessToken
        val reporters = (1..3).map { client.registerUser().accessToken }
        val flow: FlowDto = client.post(
            "/v1/flows", owner,
            CreateFlowRequest("com.spam$unique.app", "Spammy$unique offer", "sig-$unique", listOf(FlowStep("s1", 0, "vid:n", "Name", ElementKind.TEXT_FIELD, FieldType.NAME))),
        ).body()
        val listing: ListingDto = client.post("/v1/flows/${flow.id}/publish", owner, PublishRequest(description = "spammy$unique", category = "shopping")).body()
        assertFalse(listing.hidden)

        assertEquals(HttpStatusCode.BadRequest, client.post("/v1/marketplace/${listing.id}/report", owner, ReportRequest("spam")).status)
        assertEquals(HttpStatusCode.BadRequest, client.post("/v1/marketplace/${listing.id}/report", reporters[0], ReportRequest("boring")).status)

        // Two reports (one person changing their mind counts once): still listed.
        assertFalse(client.post("/v1/marketplace/${listing.id}/report", reporters[0], ReportRequest("broken")).body<ReportResponse>().hidden)
        assertFalse(client.post("/v1/marketplace/${listing.id}/report", reporters[0], ReportRequest("spam", "Ads")).body<ReportResponse>().hidden)
        assertFalse(client.post("/v1/marketplace/${listing.id}/report", reporters[1], ReportRequest("unsafe")).body<ReportResponse>().hidden)
        val detail: ListingDetailDto = client.get("/v1/marketplace/${listing.id}") { bearerAuth(reporters[0]) }.body()
        assertEquals("SPAM", detail.myReport)
        assertEquals(1, client.search(reporters[2]).size)

        // The third person hides it from everyone but its owner.
        assertTrue(client.post("/v1/marketplace/${listing.id}/report", reporters[2], ReportRequest("spam")).body<ReportResponse>().hidden)
        assertTrue(client.search(reporters[2]).isEmpty())
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/marketplace/${listing.id}") { bearerAuth(reporters[2]) }.status)
        assertEquals(HttpStatusCode.NotFound, client.post("/v1/marketplace/${listing.id}/import", reporters[2], ImportRequest()).status)
        val mine = client.get("/v1/marketplace?mine=true") { bearerAuth(owner) }.body<Page<ListingDto>>().items.single { it.id == listing.id }
        assertTrue(mine.hidden)
        assertTrue(client.get("/v1/marketplace/${listing.id}") { bearerAuth(owner) }.body<ListingDetailDto>().listing.hidden)

        // A fixed version shows it again and clears the old reports.
        val fixed: ListingDto = client.post("/v1/flows/${flow.id}/publish", owner, PublishRequest(description = "spammy$unique fixed", category = "shopping", changelog = "No ads")).body()
        assertFalse(fixed.hidden)
        assertEquals(1, client.search(reporters[2]).size)
        assertNull(client.get("/v1/marketplace/${listing.id}") { bearerAuth(reporters[0]) }.body<ListingDetailDto>().myReport)
    }
}
