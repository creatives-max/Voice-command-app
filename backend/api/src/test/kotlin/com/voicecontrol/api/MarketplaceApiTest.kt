package com.voicecontrol.api

import com.voicecontrol.api.routes.CreateFlowRequest
import com.voicecontrol.api.routes.FlowDto
import com.voicecontrol.api.routes.FlowVersionDto
import com.voicecontrol.api.routes.ImportRequest
import com.voicecontrol.api.routes.ListingDetailDto
import com.voicecontrol.api.routes.ListingDto
import com.voicecontrol.api.routes.Page
import com.voicecontrol.api.routes.PublishRequest
import com.voicecontrol.api.routes.RateRequest
import com.voicecontrol.api.routes.TemplateDto
import com.voicecontrol.api.routes.UpdateFlowRequest
import com.voicecontrol.domain.ai.ElementKind
import com.voicecontrol.domain.ai.FieldType
import com.voicecontrol.domain.flow.FlowStep
import com.voicecontrol.domain.flow.StepAction
import com.voicecontrol.domain.flow.VersionSource
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
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

class MarketplaceApiTest {
    private val unique = UUID.randomUUID().toString().take(8)
    private val app = "com.rail$unique.app"
    private val steps = listOf(
        FlowStep("s1", 0, "vid:name", "Passenger name", ElementKind.TEXT_FIELD, FieldType.NAME, question = "Passenger ka naam?", defaultValue = "Rahul Sharma"),
        FlowStep("s2", 1, "vid:age", "Age", ElementKind.TEXT_FIELD, FieldType.NUMBER, rules = listOf("max:3")),
        FlowStep("s3", 2, "vid:go", "Book", ElementKind.BUTTON, action = StepAction.CLICK),
    )

    private suspend fun HttpClient.post(path: String, token: String, body: Any): HttpResponse = post(path) {
        bearerAuth(token); contentType(ContentType.Application.Json); setBody(body)
    }

    @Test
    fun `publish, search, import, update from source and rate`() = testApplication {
        application { voiceControl(TestEnvironment.services) }
        val client = jsonClient()
        val alice = client.registerUser().accessToken
        val bob = client.registerUser().accessToken

        val flow: FlowDto = client.post("/v1/flows", alice, CreateFlowRequest(app, "Rail booking", "sig-$unique", steps)).body()
        assertEquals(HttpStatusCode.BadRequest, client.post("/v1/flows/${flow.id}/publish", alice, PublishRequest(category = "nonsense")).status)
        val listing: ListingDto = client.post(
            "/v1/flows/${flow.id}/publish", alice,
            PublishRequest(description = "Books a train ticket by voice $unique", category = "travel", tags = listOf("Train", "IRCTC", "train")),
        ).body()
        assertEquals(1, listing.latestVersion)
        assertEquals(listOf("train", "irctc"), listing.tags)
        assertTrue(listing.mine)

        // Full-text search (description word), app filter, and "mine".
        val found: Page<ListingDto> = client.get("/v1/marketplace?q=ticket%20$unique") { bearerAuth(bob) }.body()
        assertEquals(listOf(listing.id), found.items.map { it.id })
        val byApp: Page<ListingDto> = client.get("/v1/marketplace?appPackage=$app") { bearerAuth(bob) }.body()
        assertEquals(1, byApp.items.size)
        assertFalse(byApp.items.single().mine)
        val mine: Page<ListingDto> = client.get("/v1/marketplace?mine=true") { bearerAuth(alice) }.body()
        assertTrue(mine.items.any { it.id == listing.id })

        // Personal defaults are never shared.
        val detail: ListingDetailDto = client.get("/v1/marketplace/${listing.id}") { bearerAuth(bob) }.body()
        assertNull(detail.steps.first().defaultValue)
        assertEquals("Passenger ka naam?", detail.steps.first().question)
        assertNull(detail.importedFlowId)

        // Bob imports v1.
        val imported: FlowDto = client.post("/v1/marketplace/${listing.id}/import", bob, ImportRequest()).body()
        assertEquals(listing.id, imported.sourcePublishedId)
        assertEquals(1, imported.sourceVersion)
        assertEquals(app, imported.appPackage)

        // Alice edits and republishes as v2; Bob sees the update and applies it.
        client.put("/v1/flows/${flow.id}") {
            bearerAuth(alice); contentType(ContentType.Application.Json)
            setBody(UpdateFlowRequest(1, steps = steps.map { if (it.id == "s2") it.copy(question = "Umar kitni hai?") else it }))
        }
        val v2: ListingDto = client.post("/v1/flows/${flow.id}/publish", alice, PublishRequest(description = "Now asks age in Hinglish", category = "travel", changelog = "Hinglish age")).body()
        assertEquals(2, v2.latestVersion)
        val bobView: ListingDetailDto = client.get("/v1/marketplace/${listing.id}") { bearerAuth(bob) }.body()
        assertEquals(imported.id, bobView.importedFlowId)
        assertTrue(bobView.updateAvailable)
        assertEquals(listOf(2, 1), bobView.versions.map { it.version })
        val updated: FlowDto = client.post("/v1/flows/${imported.id}/update-from-source", bob, emptyMap<String, String>()).body()
        assertEquals(2, updated.sourceVersion)
        assertEquals("Umar kitni hai?", updated.steps.first { it.id == "s2" }.question)
        val history: List<FlowVersionDto> = client.get("/v1/flows/${imported.id}/versions") { bearerAuth(bob) }.body()
        assertEquals(VersionSource.IMPORT, history.first().source)
        assertEquals(HttpStatusCode.Conflict, client.post("/v1/flows/${imported.id}/update-from-source", bob, emptyMap<String, String>()).status)

        // Importing again updates the same screen's flow instead of duplicating it.
        val again: FlowDto = client.post("/v1/marketplace/${listing.id}/import", bob, ImportRequest(version = 1)).body()
        assertEquals(imported.id, again.id)

        // Ratings: one per user, not your own.
        assertEquals(HttpStatusCode.BadRequest, client.put("/v1/marketplace/${listing.id}/rating") { bearerAuth(alice); contentType(ContentType.Application.Json); setBody(RateRequest(5)) }.status)
        client.put("/v1/marketplace/${listing.id}/rating") { bearerAuth(bob); contentType(ContentType.Application.Json); setBody(RateRequest(3, "Works")) }
        val rated: ListingDto = client.put("/v1/marketplace/${listing.id}/rating") {
            bearerAuth(bob); contentType(ContentType.Application.Json); setBody(RateRequest(5, "Works great"))
        }.body()
        assertEquals(1, rated.ratingCount)
        assertEquals(5.0, rated.ratingAverage)
        assertEquals(1, rated.installCount)

        // Unpublish: gone from search, still visible to the importer.
        assertEquals(HttpStatusCode.NotFound, client.delete("/v1/marketplace/${listing.id}") { bearerAuth(bob) }.status)
        assertEquals(HttpStatusCode.NoContent, client.delete("/v1/marketplace/${listing.id}") { bearerAuth(alice) }.status)
        val gone: Page<ListingDto> = client.get("/v1/marketplace?appPackage=$app") { bearerAuth(bob) }.body()
        assertTrue(gone.items.isEmpty())
        assertEquals(HttpStatusCode.OK, client.get("/v1/marketplace/${listing.id}") { bearerAuth(bob) }.status)
        val stranger = client.registerUser().accessToken
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/marketplace/${listing.id}") { bearerAuth(stranger) }.status)
    }

    @Test
    fun `starter templates are seeded and applied, not imported`() = testApplication {
        application { voiceControl(TestEnvironment.services) }
        val client = jsonClient()
        val token = client.registerUser().accessToken
        val templates: List<TemplateDto> = client.get("/v1/marketplace/templates") { bearerAuth(token) }.body()
        assertEquals(6, templates.size)
        val address = templates.first { it.listing.category == "address" }
        assertTrue(address.keywords.getValue("pin").contains("pincode"))
        assertEquals("VoiceControl", address.listing.ownerName)
        val search: Page<ListingDto> = client.get("/v1/marketplace?templates=true&q=address") { bearerAuth(token) }.body()
        assertTrue(search.items.any { it.id == address.listing.id })
        assertEquals(HttpStatusCode.BadRequest, client.post("/v1/marketplace/${address.listing.id}/import", token, ImportRequest()).status)
        // Seeding again (another replica starting) changes nothing.
        com.voicecontrol.application.marketplace.StarterTemplates.seed(com.voicecontrol.infrastructure.persistence.JdbcMarketplaceRepository(TestEnvironment.services.database))
        val after: List<TemplateDto> = client.get("/v1/marketplace/templates") { bearerAuth(token) }.body()
        assertEquals(templates.map { it.listing.latestVersion }, after.map { it.listing.latestVersion })
    }
}
