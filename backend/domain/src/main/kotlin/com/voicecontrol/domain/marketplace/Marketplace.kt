package com.voicecontrol.domain.marketplace

import com.voicecontrol.domain.flow.FlowStep
import java.time.Instant
import java.util.UUID

/** A flow shared in the marketplace (or a built-in starter template, which has no owner). */
data class PublishedFlow(
    val id: UUID,
    val ownerId: UUID?,
    val ownerName: String?,
    val sourceFlowId: UUID?,
    val name: String,
    val description: String,
    val appPackage: String,
    val category: String,
    val tags: List<String>,
    val isTemplate: Boolean,
    /** Templates only: words that identify each step's field on any app's screen (step id → keywords). */
    val keywords: Map<String, List<String>>,
    val latestVersion: Int,
    val installCount: Int,
    val ratingCount: Int,
    val ratingSum: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** Hidden from search after reports (until the owner publishes a fixed version). */
    val hidden: Boolean = false,
) {
    val ratingAverage: Double? get() = if (ratingCount == 0) null else ratingSum.toDouble() / ratingCount
}

data class PublishedVersion(
    val publishedId: UUID,
    val version: Int,
    val steps: List<FlowStep>,
    val screenSignature: String,
    val changelog: String?,
    val createdAt: Instant,
)

data class Rating(val publishedId: UUID, val userId: UUID, val userName: String?, val stars: Int, val review: String?, val updatedAt: Instant)

enum class MarketplaceSort { POPULAR, RATING, RECENT, RELEVANCE }

data class MarketplaceQuery(
    val text: String?,
    val appPackage: String?,
    val category: String?,
    val templates: Boolean?,
    val ownerId: UUID?,
    val sort: MarketplaceSort,
    val limit: Int,
    val offset: Int,
)

/** Listing fields the publisher controls. */
data class Listing(val name: String, val description: String, val category: String, val tags: List<String>)

interface MarketplaceRepository {
    suspend fun create(published: PublishedFlow, first: PublishedVersion): PublishedFlow
    suspend fun addVersion(id: UUID, listing: Listing, version: PublishedVersion): PublishedFlow
    suspend fun get(id: UUID, includeUnpublished: Boolean = false): PublishedFlow?
    suspend fun findBySource(ownerId: UUID, sourceFlowId: UUID): PublishedFlow?
    suspend fun version(id: UUID, version: Int): PublishedVersion?
    suspend fun versions(id: UUID): List<PublishedVersion>
    suspend fun search(query: MarketplaceQuery): List<PublishedFlow>
    suspend fun unpublish(ownerId: UUID, id: UUID, at: Instant): Boolean
    suspend fun recordInstall(id: UUID)
    /** Stores (or replaces) [userId]'s report; returns how many different people reported the listing. */
    suspend fun report(id: UUID, userId: UUID, reason: ReportReason, note: String?, at: Instant): Int
    suspend fun myReport(id: UUID, userId: UUID): ReportReason?
    suspend fun hide(id: UUID, at: Instant)
    suspend fun rate(id: UUID, userId: UUID, stars: Int, review: String?, at: Instant): PublishedFlow
    suspend fun ratings(id: UUID, limit: Int): List<Rating>
    suspend fun rating(id: UUID, userId: UUID): Rating?
    /** Inserts or refreshes a built-in template (new version only when its steps changed). */
    suspend fun upsertTemplate(template: PublishedFlow, steps: List<FlowStep>, at: Instant)
}

/** Why someone reports a listing. */
enum class ReportReason { BROKEN, UNSAFE, SPAM, OTHER }
