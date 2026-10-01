package com.voicecontrol.infrastructure.persistence

import com.voicecontrol.domain.care.CareEvent
import com.voicecontrol.domain.care.CareLink
import com.voicecontrol.domain.care.CarePermission
import com.voicecontrol.domain.care.CareRepository
import com.voicecontrol.domain.care.CareStatus
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

class JdbcCareRepository(private val db: Database) : CareRepository {
    private val json = Json { ignoreUnknownKeys = true }
    private val detailsSerializer = MapSerializer(String.serializer(), String.serializer())

    private val select = """
        SELECT l.*, r.email AS receiver_email, r.name AS receiver_name, c.email AS caregiver_email, c.name AS caregiver_name
        FROM care_links l JOIN users r ON r.id = l.receiver_id LEFT JOIN users c ON c.id = l.caregiver_id
    """.trimIndent()

    private fun Connection.permissions(values: Set<CarePermission>) = createArrayOf("text", values.map { it.name }.sorted().toTypedArray())

    override suspend fun insert(link: CareLink): CareLink = db.tx {
        update(
            """
            INSERT INTO care_links (id, receiver_id, caregiver_id, permissions, status, code_hash, code_expires_at, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            link.id, link.receiverId, link.caregiverId, permissions(link.permissions), link.status.name, link.codeHash, link.codeExpiresAt, link.createdAt,
        )
        findIn(link.id)!!
    }

    override suspend fun find(id: UUID): CareLink? = db.tx { findIn(id) }

    override suspend fun findPendingByCode(codeHash: String): CareLink? = db.tx {
        query("$select WHERE l.code_hash = ? AND l.status = 'PENDING'", codeHash, map = ::toLink).firstOrNull()
    }

    override suspend fun listFor(userId: UUID): List<CareLink> = db.tx {
        query("$select WHERE (l.receiver_id = ? OR l.caregiver_id = ?) AND l.status <> 'REVOKED' ORDER BY l.created_at DESC", userId, userId, map = ::toLink)
    }

    override suspend fun accept(id: UUID, caregiverId: UUID, at: Instant): CareLink? = db.tx {
        val n = update(
            "UPDATE care_links SET caregiver_id = ?, status = 'ACTIVE', accepted_at = ?, code_hash = NULL, code_expires_at = NULL WHERE id = ? AND status = 'PENDING'",
            caregiverId, at, id,
        )
        if (n == 0) null else findIn(id)
    }

    override suspend fun setPermissions(id: UUID, permissions: Set<CarePermission>): CareLink? = db.tx {
        val n = update("UPDATE care_links SET permissions = ? WHERE id = ? AND status <> 'REVOKED'", permissions(permissions), id)
        if (n == 0) null else findIn(id)
    }

    override suspend fun revoke(id: UUID, at: Instant): Boolean = db.tx {
        update("UPDATE care_links SET status = 'REVOKED', revoked_at = ?, code_hash = NULL WHERE id = ? AND status <> 'REVOKED'", at, id) > 0
    }

    override suspend fun addEvent(linkId: UUID, actorId: UUID?, action: String, details: Map<String, String>, at: Instant) {
        db.tx {
            update(
                "INSERT INTO care_events (link_id, actor_id, action, details, at) VALUES (?, ?, ?, ?::jsonb, ?)",
                linkId, actorId, action, json.encodeToString(detailsSerializer, details), at,
            )
        }
    }

    override suspend fun events(linkId: UUID, limit: Int): List<CareEvent> = db.tx {
        query(
            """
            SELECT e.*, u.email AS actor_email FROM care_events e LEFT JOIN users u ON u.id = e.actor_id
            WHERE e.link_id = ? ORDER BY e.id DESC LIMIT ?
            """.trimIndent(),
            linkId, limit,
        ) { rs ->
            CareEvent(
                rs.getLong("id"), rs.uuid("link_id"), rs.getObject("actor_id", UUID::class.java), rs.getString("actor_email"), rs.getString("action"),
                runCatching { json.decodeFromString(detailsSerializer, rs.getString("details")) }.getOrDefault(emptyMap()), rs.instant("at"),
            )
        }
    }

    private fun Connection.findIn(id: UUID): CareLink? = query("$select WHERE l.id = ?", id, map = ::toLink).firstOrNull()

    private fun toLink(rs: ResultSet) = CareLink(
        id = rs.uuid("id"),
        receiverId = rs.uuid("receiver_id"),
        caregiverId = rs.getObject("caregiver_id", UUID::class.java),
        permissions = (rs.getArray("permissions")?.array as? Array<*>).orEmpty().mapNotNull { CarePermission.of(it.toString()) }.toSet(),
        status = CareStatus.valueOf(rs.getString("status")),
        codeHash = rs.getString("code_hash"),
        codeExpiresAt = rs.getTimestamp("code_expires_at")?.toInstant(),
        createdAt = rs.instant("created_at"),
        acceptedAt = rs.getTimestamp("accepted_at")?.toInstant(),
        revokedAt = rs.getTimestamp("revoked_at")?.toInstant(),
        receiverEmail = rs.getString("receiver_email"),
        receiverName = rs.getString("receiver_name"),
        caregiverEmail = rs.getString("caregiver_email"),
        caregiverName = rs.getString("caregiver_name"),
    )
}
