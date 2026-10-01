package com.voicecontrol.application.marketplace

import com.voicecontrol.application.flow.FlowService
import com.voicecontrol.domain.common.DomainException
import com.voicecontrol.domain.flow.FlowRepository
import com.voicecontrol.domain.flow.FlowStep
import com.voicecontrol.domain.flow.FlowWithVersion
import com.voicecontrol.domain.flow.VersionSource
import com.voicecontrol.domain.marketplace.Listing
import com.voicecontrol.domain.marketplace.MarketplaceQuery
import com.voicecontrol.domain.marketplace.MarketplaceRepository
import com.voicecontrol.domain.marketplace.MarketplaceSort
import com.voicecontrol.domain.marketplace.PublishedFlow
import com.voicecontrol.domain.marketplace.PublishedVersion
import com.voicecontrol.domain.marketplace.Rating
import java.time.Clock
import java.util.UUID

/** What the publisher fills in. */
data class PublishInput(val description: String, val category: String, val tags: List<String>, val changelog: String?, val name: String?)

/** A listing as one user sees it: details, versions, their rating and their imported copy. */
data class ListingView(
    val listing: PublishedFlow,
    val latest: PublishedVersion,
    val versions: List<PublishedVersion>,
    val myRating: Rating?,
    val reviews: List<Rating>,
    /** The user's flow that follows this listing, if they imported it. */
    val importedFlowId: UUID?,
    val importedVersion: Int?,
) {
    val updateAvailable: Boolean get() = importedVersion != null && importedVersion < listing.latestVersion
}

/**
 * The flow marketplace: publish a flow (a sanitized, versioned copy), browse and search others',
 * import into your account (version-aware: the copy remembers its source and can be updated), and
 * rate listings. Built-in starter templates live here too, as owner-less listings.
 */
class MarketplaceService(
    private val repo: MarketplaceRepository,
    private val flows: FlowRepository,
    private val flowService: FlowService,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun publish(userId: UUID, flowId: UUID, input: PublishInput): PublishedFlow {
        val flow = flows.find(userId, flowId) ?: throw DomainException.NotFound("Flow not found")
        if (flow.flow.sourcePublishedId != null) {
            repo.get(flow.flow.sourcePublishedId!!, includeUnpublished = true)?.let { source ->
                if (source.ownerId != userId) throw DomainException.Validation("Imported flows can't be republished; publish your own flows")
            }
        }
        val listing = listing(input, input.name ?: flow.flow.name)
        val steps = sanitize(flow.version.steps)
        if (steps.none { it.action.targetsElement }) throw DomainException.Validation("This flow has no steps to share")
        val now = clock.instant()
        val existing = repo.findBySource(userId, flowId)
        return if (existing == null) {
            val id = UUID.randomUUID()
            repo.create(
                PublishedFlow(
                    id, userId, null, flowId, listing.name, listing.description, flow.flow.appPackage, listing.category, listing.tags,
                    isTemplate = false, keywords = emptyMap(), latestVersion = 1, installCount = 0, ratingCount = 0, ratingSum = 0,
                    createdAt = now, updatedAt = now,
                ),
                PublishedVersion(id, 1, steps, flow.flow.screenSignature, cleanChangelog(input.changelog) ?: "First version", now),
            )
        } else {
            val next = existing.latestVersion + 1
            repo.addVersion(existing.id, listing, PublishedVersion(existing.id, next, steps, flow.flow.screenSignature, cleanChangelog(input.changelog), now))
        }
    }

    suspend fun unpublish(userId: UUID, id: UUID) {
        if (!repo.unpublish(userId, id, clock.instant())) throw DomainException.NotFound("Listing not found")
    }

    suspend fun search(query: MarketplaceQuery): List<PublishedFlow> =
        repo.search(query.copy(limit = query.limit.coerceIn(1, 100), offset = query.offset.coerceAtLeast(0), category = query.category?.takeIf { it in CATEGORIES }))

    suspend fun templates(): List<Pair<PublishedFlow, PublishedVersion>> =
        repo.search(MarketplaceQuery(null, null, null, templates = true, ownerId = null, sort = MarketplaceSort.RECENT, limit = 100, offset = 0))
            .mapNotNull { t -> repo.version(t.id, t.latestVersion)?.let { t to it } }

    suspend fun view(userId: UUID, id: UUID): ListingView {
        val listing = repo.get(id, includeUnpublished = true) ?: throw DomainException.NotFound("Listing not found")
        val mine = importedCopy(userId, id)
        // Unpublished listings stay visible to their owner and to people who imported them.
        if (repo.get(id) == null && listing.ownerId != userId && mine == null) throw DomainException.NotFound("Listing not found")
        val versions = repo.versions(id)
        val latest = versions.firstOrNull { it.version == listing.latestVersion } ?: throw DomainException.NotFound("Listing not found")
        return ListingView(listing, latest, versions, repo.rating(id, userId), repo.ratings(id, 20), mine?.flow?.id, mine?.flow?.sourceVersion)
    }

    /**
     * Imports a listing as a flow in the user's account. If they already have a flow for the same screen,
     * the listing's steps become a new version of it (their history keeps the old steps).
     */
    suspend fun import(userId: UUID, id: UUID, version: Int?): FlowWithVersion {
        val listing = repo.get(id) ?: throw DomainException.NotFound("Listing not found")
        if (listing.isTemplate) throw DomainException.Validation("Templates are applied to one of your flows instead of imported")
        val v = repo.version(id, version ?: listing.latestVersion) ?: throw DomainException.NotFound("Version not found")
        val saved = flowService.importFlow(userId, listing.appPackage, listing.name, v.screenSignature, v.steps, "Imported “${listing.name}” v${v.version}")
        flows.linkSource(userId, saved.flow.id, id, v.version)
        if (saved.flow.sourcePublishedId != id) repo.recordInstall(id)
        return flowService.get(userId, saved.flow.id)
    }

    /** Brings an imported flow up to the listing's latest version (as a new version of the user's flow). */
    suspend fun updateFromSource(userId: UUID, flowId: UUID): FlowWithVersion {
        val flow = flowService.get(userId, flowId)
        val sourceId = flow.flow.sourcePublishedId ?: throw DomainException.Validation("This flow wasn't imported from the marketplace")
        val listing = repo.get(sourceId) ?: throw DomainException.NotFound("The original listing is no longer published")
        if ((flow.flow.sourceVersion ?: 0) >= listing.latestVersion) throw DomainException.Conflict("Already up to date")
        val latest = repo.version(sourceId, listing.latestVersion) ?: throw DomainException.NotFound("Version not found")
        val saved = flowService.update(
            userId, flowId, flow.flow.currentVersion, null, latest.steps, "Updated from “${listing.name}” v${latest.version}", VersionSource.IMPORT,
        )
        flows.linkSource(userId, flowId, sourceId, latest.version)
        return flowService.get(userId, saved.flow.id)
    }

    suspend fun rate(userId: UUID, id: UUID, stars: Int, review: String?): PublishedFlow {
        val listing = repo.get(id) ?: throw DomainException.NotFound("Listing not found")
        if (listing.ownerId == userId) throw DomainException.Validation("You can't rate your own flow")
        if (stars !in 1..5) throw DomainException.Validation("Rate from 1 to 5 stars")
        val text = review?.trim()?.takeIf { it.isNotEmpty() }
        if ((text?.length ?: 0) > MAX_REVIEW) throw DomainException.Validation("Reviews can be at most $MAX_REVIEW characters")
        return repo.rate(id, userId, stars, text, clock.instant())
    }

    private suspend fun importedCopy(userId: UUID, publishedId: UUID): FlowWithVersion? =
        flows.list(userId, null, 500, 0).firstOrNull { it.sourcePublishedId == publishedId }?.let { flows.find(userId, it.id) }

    private fun listing(input: PublishInput, name: String): Listing {
        val n = name.trim()
        if (n.length !in 1..120) throw DomainException.Validation("Name must be 1-120 characters")
        val description = input.description.trim()
        if (description.length > MAX_DESCRIPTION) throw DomainException.Validation("Description can be at most $MAX_DESCRIPTION characters")
        val category = input.category.trim().lowercase()
        if (category !in CATEGORIES) throw DomainException.Validation("Choose a category: ${CATEGORIES.joinToString(", ")}")
        val tags = input.tags.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
        if (tags.size > MAX_TAGS || tags.any { it.length > 24 }) throw DomainException.Validation("Use at most $MAX_TAGS tags of up to 24 characters")
        return Listing(n, description, category, tags)
    }

    private fun cleanChangelog(text: String?) = text?.trim()?.take(500)?.takeIf { it.isNotEmpty() }

    companion object {
        val CATEGORIES = listOf("signup", "login", "address", "contact", "payment", "shopping", "banking", "government", "travel", "health", "other")
        const val MAX_DESCRIPTION = 1_000
        const val MAX_TAGS = 8
        const val MAX_REVIEW = 500

        /** What is shared: default values are removed (they may be personal); questions, rules and logic are kept. */
        fun sanitize(steps: List<FlowStep>): List<FlowStep> = steps.map { it.copy(defaultValue = null) }
    }
}
