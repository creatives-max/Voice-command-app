package com.voicecontrol.api.routes

import com.voicecontrol.api.plugins.JWT_AUTH
import com.voicecontrol.api.plugins.userId
import com.voicecontrol.application.marketplace.MarketplaceService
import com.voicecontrol.application.marketplace.PublishInput
import com.voicecontrol.domain.common.DomainException
import com.voicecontrol.domain.flow.FlowStep
import com.voicecontrol.domain.marketplace.MarketplaceQuery
import com.voicecontrol.domain.marketplace.MarketplaceSort
import com.voicecontrol.domain.marketplace.PublishedFlow
import com.voicecontrol.domain.marketplace.PublishedVersion
import com.voicecontrol.domain.marketplace.Rating
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable data class PublishRequest(
    val name: String? = null,
    val description: String = "",
    val category: String,
    val tags: List<String> = emptyList(),
    val changelog: String? = null,
)

@Serializable data class ListingDto(
    val id: String,
    val name: String,
    val description: String,
    val appPackage: String,
    val category: String,
    val tags: List<String>,
    val isTemplate: Boolean,
    val ownerName: String? = null,
    val mine: Boolean,
    val latestVersion: Int,
    val installCount: Int,
    val ratingAverage: Double? = null,
    val ratingCount: Int,
    val createdAt: String,
    val updatedAt: String,
) {
    companion object {
        fun from(p: PublishedFlow, userId: UUID) = ListingDto(
            p.id.toString(), p.name, p.description, p.appPackage, p.category, p.tags, p.isTemplate,
            if (p.isTemplate) "VoiceControl" else p.ownerName, p.ownerId == userId, p.latestVersion, p.installCount,
            p.ratingAverage?.let { Math.round(it * 10) / 10.0 }, p.ratingCount, p.createdAt.toString(), p.updatedAt.toString(),
        )
    }
}

@Serializable data class ListingVersionDto(val version: Int, val changelog: String? = null, val stepCount: Int, val createdAt: String) {
    companion object {
        fun from(v: PublishedVersion) = ListingVersionDto(v.version, v.changelog, v.steps.size, v.createdAt.toString())
    }
}

@Serializable data class RatingDto(val stars: Int, val review: String? = null, val userName: String? = null, val updatedAt: String) {
    companion object {
        fun from(r: Rating) = RatingDto(r.stars, r.review, r.userName ?: "VoiceControl user", r.updatedAt.toString())
    }
}

@Serializable data class ListingDetailDto(
    val listing: ListingDto,
    val steps: List<FlowStep>,
    val keywords: Map<String, List<String>> = emptyMap(),
    val versions: List<ListingVersionDto>,
    val myRating: RatingDto? = null,
    val reviews: List<RatingDto>,
    val importedFlowId: String? = null,
    val importedVersion: Int? = null,
    val updateAvailable: Boolean,
)

@Serializable data class TemplateDto(val listing: ListingDto, val steps: List<FlowStep>, val keywords: Map<String, List<String>>)
@Serializable data class ImportRequest(val version: Int? = null)
@Serializable data class RateRequest(val stars: Int, val review: String? = null)

private fun ApplicationCall.uuidParam(name: String): UUID =
    runCatching { UUID.fromString(parameters[name]) }.getOrElse { throw DomainException.Validation("$name must be a UUID") }

fun Route.marketplaceRoutes(market: MarketplaceService) {
    authenticate(JWT_AUTH) {
        route("/v1/marketplace") {
            get {
                val q = call.request.queryParameters
                val limit = q["limit"]?.toIntOrNull() ?: 24
                val offset = q["offset"]?.toIntOrNull() ?: 0
                val sort = q["sort"]?.uppercase()?.let { runCatching { MarketplaceSort.valueOf(it) }.getOrNull() }
                    ?: if (!q["q"].isNullOrBlank()) MarketplaceSort.RELEVANCE else MarketplaceSort.POPULAR
                val userId = call.userId
                val items = market.search(
                    MarketplaceQuery(
                        text = q["q"],
                        appPackage = q["appPackage"]?.takeIf { it.isNotBlank() },
                        category = q["category"]?.takeIf { it.isNotBlank() },
                        templates = q["templates"]?.toBooleanStrictOrNull(),
                        ownerId = if (q["mine"] == "true") userId else null,
                        sort = sort,
                        limit = limit,
                        offset = offset,
                    ),
                ).map { ListingDto.from(it, userId) }
                call.respond(Page(items, limit, offset))
            }
            get("/categories") { call.respond(MarketplaceService.CATEGORIES) }
            get("/templates") {
                val userId = call.userId
                call.respond(market.templates().map { (t, v) -> TemplateDto(ListingDto.from(t, userId), v.steps, t.keywords) })
            }
            route("/{id}") {
                get {
                    val userId = call.userId
                    val view = market.view(userId, call.uuidParam("id"))
                    call.respond(
                        ListingDetailDto(
                            listing = ListingDto.from(view.listing, userId),
                            steps = view.latest.steps,
                            keywords = view.listing.keywords,
                            versions = view.versions.map(ListingVersionDto::from),
                            myRating = view.myRating?.let(RatingDto::from),
                            reviews = view.reviews.map(RatingDto::from),
                            importedFlowId = view.importedFlowId?.toString(),
                            importedVersion = view.importedVersion,
                            updateAvailable = view.updateAvailable,
                        ),
                    )
                }
                delete {
                    market.unpublish(call.userId, call.uuidParam("id"))
                    call.respond(HttpStatusCode.NoContent)
                }
                post("/import") {
                    val body = call.receive<ImportRequest>()
                    call.respond(HttpStatusCode.Created, FlowDto.from(market.import(call.userId, call.uuidParam("id"), body.version)))
                }
                put("/rating") {
                    val body = call.receive<RateRequest>()
                    val userId = call.userId
                    call.respond(ListingDto.from(market.rate(userId, call.uuidParam("id"), body.stars, body.review), userId))
                }
            }
        }
        post("/v1/flows/{id}/publish") {
            val body = call.receive<PublishRequest>()
            val userId = call.userId
            val listing = market.publish(userId, call.uuidParam("id"), PublishInput(body.description, body.category, body.tags, body.changelog, body.name))
            call.respond(ListingDto.from(listing, userId))
        }
        post("/v1/flows/{id}/update-from-source") {
            call.respond(FlowDto.from(market.updateFromSource(call.userId, call.uuidParam("id"))))
        }
    }
}
