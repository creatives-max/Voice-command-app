package com.voicecontrol.application.care

import com.voicecontrol.domain.care.CareEvent
import com.voicecontrol.domain.care.CareLink
import com.voicecontrol.domain.care.CarePermission
import com.voicecontrol.domain.care.CareRepository
import com.voicecontrol.domain.care.CareStatus
import com.voicecontrol.domain.common.DomainException
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.util.UUID

/** A caregiver working on the account of the person they help, through an active [link]. */
data class ActingAs(val link: CareLink, val caregiverId: UUID) {
    val receiverId: UUID get() = link.receiverId
}

/**
 * Remote caregiver mode with consent. The person to be helped creates an invite code with the
 * permissions they agree to; the caregiver enters it to link the accounts. Either side can end the
 * link; the person helped can change its permissions; every change and every action is recorded and
 * visible to both.
 */
class CareService(
    private val links: CareRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val random: SecureRandom = SecureRandom(),
) {
    /** Creates an invite; returns the link and its one-time code (shown once, valid for [CODE_TTL]). */
    suspend fun invite(receiverId: UUID, permissions: Set<CarePermission>): Pair<CareLink, String> {
        val mine = links.listFor(receiverId)
        if (mine.count { it.receiverId == receiverId && it.status == CareStatus.PENDING } >= MAX_PENDING) {
            throw DomainException.Validation("You already have $MAX_PENDING open invites; cancel one first")
        }
        if (mine.count { it.receiverId == receiverId && it.status == CareStatus.ACTIVE } >= MAX_CAREGIVERS) {
            throw DomainException.Validation("You can have at most $MAX_CAREGIVERS caregivers")
        }
        val code = newCode()
        val now = clock.instant()
        val link = links.insert(
            CareLink(UUID.randomUUID(), receiverId, null, permissions, CareStatus.PENDING, hash(code), now.plus(CODE_TTL), now),
        )
        links.addEvent(link.id, receiverId, "invited", mapOf("permissions" to permissions.joinToString(",") { it.id }), now)
        return link to format(code)
    }

    /** The caregiver enters the code they were given. */
    suspend fun accept(caregiverId: UUID, code: String): CareLink {
        val clean = code.uppercase().filter { it.isLetterOrDigit() }
        val now = clock.instant()
        val link = clean.takeIf { it.length == CODE_LENGTH }?.let { links.findPendingByCode(hash(it)) }
            ?.takeIf { it.codeExpiresAt?.isAfter(now) == true }
            ?: throw DomainException.NotFound("This code is wrong or has expired. Ask for a new one.")
        if (link.receiverId == caregiverId) throw DomainException.Validation("You can't be your own caregiver")
        if (links.listFor(caregiverId).any { it.receiverId == link.receiverId && it.caregiverId == caregiverId && it.status == CareStatus.ACTIVE }) {
            throw DomainException.Conflict("You already help this person")
        }
        val accepted = links.accept(link.id, caregiverId, now) ?: throw DomainException.NotFound("This code is wrong or has expired. Ask for a new one.")
        links.addEvent(link.id, caregiverId, "accepted", emptyMap(), now)
        return links.find(accepted.id) ?: accepted
    }

    suspend fun list(userId: UUID): List<CareLink> {
        val now = clock.instant()
        return links.listFor(userId).filter { it.status != CareStatus.PENDING || (it.receiverId == userId && it.codeExpiresAt?.isAfter(now) == true) }
    }

    private suspend fun visible(userId: UUID, id: UUID): CareLink =
        links.find(id)?.takeIf { it.status != CareStatus.REVOKED && (it.receiverId == userId || it.caregiverId == userId) }
            ?: throw DomainException.NotFound("Not found")

    /** Only the person helped can change what their caregiver may do. */
    suspend fun setPermissions(receiverId: UUID, id: UUID, permissions: Set<CarePermission>): CareLink {
        val link = visible(receiverId, id)
        if (link.receiverId != receiverId) throw DomainException.Forbidden("Only the person being helped can change permissions")
        val updated = links.setPermissions(id, permissions) ?: throw DomainException.NotFound("Not found")
        links.addEvent(id, receiverId, "permissions_changed", mapOf("permissions" to permissions.joinToString(",") { it.id }), clock.instant())
        return links.find(updated.id) ?: updated
    }

    /** Ends a link (or cancels an invite); either side may. */
    suspend fun revoke(userId: UUID, id: UUID) {
        val link = visible(userId, id)
        val now = clock.instant()
        links.addEvent(link.id, userId, if (link.status == CareStatus.PENDING) "invite_cancelled" else "ended", emptyMap(), now)
        links.revoke(link.id, now)
    }

    suspend fun events(userId: UUID, id: UUID, limit: Int): List<CareEvent> {
        visible(userId, id)
        return links.events(id, limit.coerceIn(1, 200))
    }

    /** Checks that [caregiverId] may act through link [id] with [needs]; throws Forbidden otherwise. */
    suspend fun actingAs(caregiverId: UUID, id: UUID, needs: CarePermission?): ActingAs {
        val link = links.find(id)?.takeIf { it.status == CareStatus.ACTIVE && it.caregiverId == caregiverId }
            ?: throw DomainException.Forbidden("You no longer help this person")
        if (needs != null && needs !in link.permissions) {
            throw DomainException.Forbidden("${link.receiverName ?: link.receiverEmail ?: "This person"} hasn't allowed you to ${needs.description.lowercase()}")
        }
        return ActingAs(link, caregiverId)
    }

    suspend fun record(acting: ActingAs, action: String, details: Map<String, String>) {
        links.addEvent(acting.link.id, acting.caregiverId, action, details, clock.instant())
    }

    private fun newCode(): String = (1..CODE_LENGTH).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")

    companion object {
        const val CODE_LENGTH = 8
        const val MAX_PENDING = 5
        const val MAX_CAREGIVERS = 10
        val CODE_TTL: Duration = Duration.ofMinutes(30)
        /** No 0/O, 1/I/L: easy to read out over the phone. */
        private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

        fun format(code: String) = code.chunked(4).joinToString("-")

        fun hash(code: String): String =
            MessageDigest.getInstance("SHA-256").digest(code.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
