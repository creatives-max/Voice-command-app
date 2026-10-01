package com.voicecontrol.domain.care

import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

/**
 * What a caregiver may do for the person they help. Seeing the person's flows, triggers, phones and
 * runs is part of every link; these add to it.
 */
@Serializable
enum class CarePermission(val id: String, val description: String) {
    EDIT_FLOWS("edit_flows", "Edit flows and when they run"),
    RUN_FLOWS("run_flows", "Run flows on the phone"),
    VIEW_HISTORY("view_history", "See run history"),
    ;

    companion object {
        fun of(id: String): CarePermission? = entries.firstOrNull { it.id == id || it.name == id }
    }
}

@Serializable
enum class CareStatus { PENDING, ACTIVE, REVOKED }

/**
 * Consent for [caregiverId] to help [receiverId]. The person being helped creates the link (a short
 * code they share), chooses its permissions and can change or end it at any time; so can the caregiver.
 */
data class CareLink(
    val id: UUID,
    val receiverId: UUID,
    val caregiverId: UUID?,
    val permissions: Set<CarePermission>,
    val status: CareStatus,
    val codeHash: String?,
    val codeExpiresAt: Instant?,
    val createdAt: Instant,
    val acceptedAt: Instant? = null,
    val revokedAt: Instant? = null,
    val receiverEmail: String? = null,
    val receiverName: String? = null,
    val caregiverEmail: String? = null,
    val caregiverName: String? = null,
)

/** Something done on a link: accepted, permissions changed, a flow edited or run, ended. */
data class CareEvent(val id: Long, val linkId: UUID, val actorId: UUID?, val actorEmail: String?, val action: String, val details: Map<String, String>, val at: Instant)

interface CareRepository {
    suspend fun insert(link: CareLink): CareLink
    suspend fun find(id: UUID): CareLink?
    suspend fun findPendingByCode(codeHash: String): CareLink?
    /** Links where [userId] is the person helped or the caregiver (not ended). */
    suspend fun listFor(userId: UUID): List<CareLink>
    /** Activates a pending link for [caregiverId]; null if it is no longer pending. */
    suspend fun accept(id: UUID, caregiverId: UUID, at: Instant): CareLink?
    suspend fun setPermissions(id: UUID, permissions: Set<CarePermission>): CareLink?
    suspend fun revoke(id: UUID, at: Instant): Boolean
    suspend fun addEvent(linkId: UUID, actorId: UUID?, action: String, details: Map<String, String>, at: Instant)
    suspend fun events(linkId: UUID, limit: Int): List<CareEvent>
}
