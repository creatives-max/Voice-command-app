package com.voicecontrol.infrastructure.persistence

import com.voicecontrol.domain.common.DomainException
import com.voicecontrol.domain.flow.FlowStep
import com.voicecontrol.domain.marketplace.Listing
import com.voicecontrol.domain.marketplace.MarketplaceQuery
import com.voicecontrol.domain.marketplace.MarketplaceRepository
import com.voicecontrol.domain.marketplace.MarketplaceSort
import com.voicecontrol.domain.marketplace.PublishedFlow
import com.voicecontrol.domain.marketplace.PublishedVersion
import com.voicecontrol.domain.marketplace.Rating
import com.voicecontrol.domain.marketplace.ReportReason
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

class JdbcMarketplaceRepository(private val db: Database) : MarketplaceRepository {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private val stepsSerializer = ListSerializer(FlowStep.serializer())
    private val keywordsSerializer = MapSerializer(String.serializer(), ListSerializer(String.serializer()))

    /** Weighted full-text document: name (A), description (B), app + tags + category (C). */
    private val searchSql =
        "setweight(to_tsvector('simple', ?), 'A') || setweight(to_tsvector('simple', ?), 'B') || setweight(to_tsvector('simple', ?), 'C')"

    private fun searchParams(name: String, description: String, appPackage: String, category: String, tags: List<String>) =
        arrayOf<Any?>(name, description, (appPackage.replace('.', ' ') + " " + category + " " + tags.joinToString(" ")))

    private val select = "SELECT p.*, u.name AS owner_name FROM published_flows p LEFT JOIN users u ON u.id = p.owner_id"

    override suspend fun create(published: PublishedFlow, first: PublishedVersion): PublishedFlow = db.tx {
        val p = published
        update(
            """
            INSERT INTO published_flows (id, owner_id, source_flow_id, name, description, app_package, category, tags, is_template,
                match_keywords, latest_version, search, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, $searchSql, ?, ?)
            """.trimIndent(),
            p.id, p.ownerId, p.sourceFlowId, p.name, p.description, p.appPackage, p.category, tagsArray(p.tags), p.isTemplate,
            json.encodeToString(keywordsSerializer, p.keywords), first.version,
            *searchParams(p.name, p.description, p.appPackage, p.category, p.tags), p.createdAt, p.updatedAt,
        )
        insertVersion(first)
        getIn(p.id, true)!!
    }

    override suspend fun addVersion(id: UUID, listing: Listing, version: PublishedVersion): PublishedFlow = db.tx {
        val current = getIn(id, true) ?: throw DomainException.NotFound("Listing not found")
        update(
            """
            UPDATE published_flows SET name = ?, description = ?, category = ?, tags = ?, latest_version = ?, updated_at = ?,
                unpublished_at = NULL, hidden_at = NULL, search = $searchSql
            WHERE id = ?
            """.trimIndent(),
            listing.name, listing.description, listing.category, tagsArray(listing.tags), version.version, version.createdAt,
            *searchParams(listing.name, listing.description, current.appPackage, listing.category, listing.tags), id,
        )
        insertVersion(version)
        // A fixed version starts over: earlier reports are about the old steps.
        update("DELETE FROM listing_reports WHERE published_id = ?", id)
        getIn(id, true)!!
    }

    override suspend fun report(id: UUID, userId: UUID, reason: ReportReason, note: String?, at: Instant): Int = db.tx {
        update(
            """
            INSERT INTO listing_reports (published_id, user_id, reason, note, created_at) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (published_id, user_id) DO UPDATE SET reason = EXCLUDED.reason, note = EXCLUDED.note, created_at = EXCLUDED.created_at
            """.trimIndent(),
            id, userId, reason.name, note, at,
        )
        query("SELECT count(*) AS n FROM listing_reports WHERE published_id = ?", id) { it.getInt("n") }.first()
    }

    override suspend fun myReport(id: UUID, userId: UUID): ReportReason? = db.tx {
        query("SELECT reason FROM listing_reports WHERE published_id = ? AND user_id = ?", id, userId) { ReportReason.valueOf(it.getString("reason")) }.firstOrNull()
    }

    override suspend fun hide(id: UUID, at: Instant) {
        db.tx { update("UPDATE published_flows SET hidden_at = ? WHERE id = ? AND hidden_at IS NULL", at, id) }
    }

    override suspend fun get(id: UUID, includeUnpublished: Boolean): PublishedFlow? = db.tx { getIn(id, includeUnpublished) }

    private fun Connection.getIn(id: UUID, includeUnpublished: Boolean): PublishedFlow? =
        query("$select WHERE p.id = ?" + if (includeUnpublished) "" else " AND p.unpublished_at IS NULL AND p.hidden_at IS NULL", id, map = ::toPublished).firstOrNull()

    override suspend fun findBySource(ownerId: UUID, sourceFlowId: UUID): PublishedFlow? = db.tx {
        query("$select WHERE p.owner_id = ? AND p.source_flow_id = ?", ownerId, sourceFlowId, map = ::toPublished).firstOrNull()
    }

    override suspend fun version(id: UUID, version: Int): PublishedVersion? = db.tx {
        query("SELECT * FROM published_flow_versions WHERE published_id = ? AND version = ?", id, version, map = ::toVersion).firstOrNull()
    }

    override suspend fun versions(id: UUID): List<PublishedVersion> = db.tx {
        query("SELECT * FROM published_flow_versions WHERE published_id = ? ORDER BY version DESC", id, map = ::toVersion)
    }

    override suspend fun search(query: MarketplaceQuery): List<PublishedFlow> = db.tx {
        val where = mutableListOf("p.unpublished_at IS NULL")
        // Reported-and-hidden listings only show in their owner's own list.
        if (query.ownerId == null) where += "p.hidden_at IS NULL"
        val params = mutableListOf<Any?>()
        val text = query.text?.trim()?.takeIf { it.isNotEmpty() }
        if (text != null) {
            where += "(p.search @@ websearch_to_tsquery('simple', ?) OR p.name ILIKE ? OR p.app_package ILIKE ?)"
            val like = "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
            params.addAll(listOf(text, like, like))
        }
        query.appPackage?.let { where += "p.app_package = ?"; params += it }
        query.category?.let { where += "p.category = ?"; params += it }
        query.templates?.let { where += "p.is_template = ?"; params += it }
        query.ownerId?.let { where += "p.owner_id = ?"; params += it }
        val order = when {
            query.sort == MarketplaceSort.RELEVANCE && text != null -> {
                params += text
                "ts_rank(p.search, websearch_to_tsquery('simple', ?)) DESC, p.install_count DESC"
            }
            query.sort == MarketplaceSort.RATING -> "(p.rating_sum::float / greatest(p.rating_count, 1)) DESC, p.rating_count DESC"
            query.sort == MarketplaceSort.RECENT -> "p.updated_at DESC"
            else -> "p.install_count DESC, p.updated_at DESC"
        }
        params.addAll(listOf(query.limit, query.offset))
        query(
            "$select WHERE ${where.joinToString(" AND ")} ORDER BY $order, p.id LIMIT ? OFFSET ?",
            *params.toTypedArray(),
            map = ::toPublished,
        )
    }

    override suspend fun unpublish(ownerId: UUID, id: UUID, at: Instant): Boolean = db.tx {
        update("UPDATE published_flows SET unpublished_at = ? WHERE id = ? AND owner_id = ? AND unpublished_at IS NULL", at, id, ownerId) > 0
    }

    override suspend fun recordInstall(id: UUID) = db.tx {
        update("UPDATE published_flows SET install_count = install_count + 1 WHERE id = ?", id)
        Unit
    }

    override suspend fun rate(id: UUID, userId: UUID, stars: Int, review: String?, at: Instant): PublishedFlow = db.tx {
        val previous = query(
            "SELECT stars FROM flow_ratings WHERE published_id = ? AND user_id = ? FOR UPDATE",
            id, userId,
        ) { it.getInt("stars") }.firstOrNull()
        if (previous == null) {
            update(
                "INSERT INTO flow_ratings (published_id, user_id, stars, review, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
                id, userId, stars, review, at, at,
            )
            update("UPDATE published_flows SET rating_sum = rating_sum + ?, rating_count = rating_count + 1 WHERE id = ?", stars, id)
        } else {
            update("UPDATE flow_ratings SET stars = ?, review = ?, updated_at = ? WHERE published_id = ? AND user_id = ?", stars, review, at, id, userId)
            update("UPDATE published_flows SET rating_sum = rating_sum + ? WHERE id = ?", stars - previous, id)
        }
        getIn(id, true)!!
    }

    override suspend fun ratings(id: UUID, limit: Int): List<Rating> = db.tx {
        query(
            """
            SELECT r.*, u.name AS user_name FROM flow_ratings r JOIN users u ON u.id = r.user_id
            WHERE r.published_id = ? ORDER BY (r.review IS NULL), r.updated_at DESC LIMIT ?
            """.trimIndent(),
            id, limit, map = ::toRating,
        )
    }

    override suspend fun rating(id: UUID, userId: UUID): Rating? = db.tx {
        query(
            "SELECT r.*, u.name AS user_name FROM flow_ratings r JOIN users u ON u.id = r.user_id WHERE r.published_id = ? AND r.user_id = ?",
            id, userId, map = ::toRating,
        ).firstOrNull()
    }

    override suspend fun upsertTemplate(template: PublishedFlow, steps: List<FlowStep>, at: Instant) = db.tx {
        val existing = getIn(template.id, true)
        if (existing == null) {
            val t = template
            update(
                """
                INSERT INTO published_flows (id, owner_id, source_flow_id, name, description, app_package, category, tags, is_template,
                    match_keywords, latest_version, search, created_at, updated_at)
                VALUES (?, NULL, NULL, ?, ?, ?, ?, ?, TRUE, ?::jsonb, 1, $searchSql, ?, ?)
                ON CONFLICT (id) DO NOTHING
                """.trimIndent(),
                t.id, t.name, t.description, t.appPackage, t.category, tagsArray(t.tags), json.encodeToString(keywordsSerializer, t.keywords),
                *searchParams(t.name, t.description, t.appPackage, t.category, t.tags), at, at,
            )
            insertVersion(PublishedVersion(t.id, 1, steps, "", "Starter template", at), ignoreConflict = true)
            return@tx
        }
        val latest = query(
            "SELECT * FROM published_flow_versions WHERE published_id = ? AND version = ?",
            template.id, existing.latestVersion, map = ::toVersion,
        ).firstOrNull()
        val changed = latest?.steps != steps
        val next = if (changed) existing.latestVersion + 1 else existing.latestVersion
        update(
            """
            UPDATE published_flows SET name = ?, description = ?, category = ?, tags = ?, match_keywords = ?::jsonb, latest_version = ?,
                updated_at = CASE WHEN ? THEN ? ELSE updated_at END, search = $searchSql
            WHERE id = ?
            """.trimIndent(),
            template.name, template.description, template.category, tagsArray(template.tags), json.encodeToString(keywordsSerializer, template.keywords),
            next, changed, at, *searchParams(template.name, template.description, template.appPackage, template.category, template.tags), template.id,
        )
        if (changed) insertVersion(PublishedVersion(template.id, next, steps, "", "Template updated", at), ignoreConflict = true)
    }

    private fun Connection.tagsArray(tags: List<String>) = createArrayOf("text", tags.toTypedArray())

    private fun Connection.insertVersion(v: PublishedVersion, ignoreConflict: Boolean = false) {
        update(
            "INSERT INTO published_flow_versions (published_id, version, steps, screen_signature, changelog, created_at) VALUES (?, ?, ?::jsonb, ?, ?, ?)" +
                if (ignoreConflict) " ON CONFLICT DO NOTHING" else "",
            v.publishedId, v.version, json.encodeToString(stepsSerializer, v.steps), v.screenSignature, v.changelog, v.createdAt,
        )
    }

    private fun toPublished(rs: ResultSet) = PublishedFlow(
        id = rs.uuid("id"),
        ownerId = rs.getObject("owner_id", UUID::class.java),
        ownerName = rs.getString("owner_name"),
        sourceFlowId = rs.getObject("source_flow_id", UUID::class.java),
        name = rs.getString("name"),
        description = rs.getString("description"),
        appPackage = rs.getString("app_package"),
        category = rs.getString("category"),
        tags = (rs.getArray("tags")?.array as? Array<*>)?.map { it.toString() }.orEmpty(),
        isTemplate = rs.getBoolean("is_template"),
        keywords = json.decodeFromString(keywordsSerializer, rs.getString("match_keywords")),
        latestVersion = rs.getInt("latest_version"),
        installCount = rs.getInt("install_count"),
        ratingCount = rs.getInt("rating_count"),
        ratingSum = rs.getInt("rating_sum"),
        createdAt = rs.instant("created_at"),
        updatedAt = rs.instant("updated_at"),
        hidden = rs.getTimestamp("hidden_at") != null,
    )

    private fun toVersion(rs: ResultSet) = PublishedVersion(
        publishedId = rs.uuid("published_id"),
        version = rs.getInt("version"),
        steps = json.decodeFromString(stepsSerializer, rs.getString("steps")),
        screenSignature = rs.getString("screen_signature"),
        changelog = rs.getString("changelog"),
        createdAt = rs.instant("created_at"),
    )

    private fun toRating(rs: ResultSet) = Rating(
        publishedId = rs.uuid("published_id"),
        userId = rs.uuid("user_id"),
        userName = rs.getString("user_name"),
        stars = rs.getInt("stars"),
        review = rs.getString("review"),
        updatedAt = rs.instant("updated_at"),
    )
}
