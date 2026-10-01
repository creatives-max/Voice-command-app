package com.voicecontrol.application.org

import com.voicecontrol.domain.common.DomainException
import com.voicecontrol.domain.org.Actor
import com.voicecontrol.domain.org.ApiKey
import com.voicecontrol.domain.org.ApiKeyRepository
import com.voicecontrol.domain.org.ApiScope
import com.voicecontrol.domain.org.AuditEntry
import com.voicecontrol.domain.org.AuditLog
import com.voicecontrol.domain.org.AuditRepository
import com.voicecontrol.domain.org.Invitation
import com.voicecontrol.domain.org.Member
import com.voicecontrol.domain.org.OrgRepository
import com.voicecontrol.domain.org.OrgWithRole
import com.voicecontrol.domain.org.Organization
import com.voicecontrol.domain.org.Role
import com.voicecontrol.domain.user.UserRepository
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

/** Random secrets and their stored hashes (only hashes of tokens and API keys are kept). */
object Secrets {
    private val random = SecureRandom()

    fun token(bytes: Int = 32): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also(random::nextBytes))

    fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}

/** Role checks shared by every organization-aware service. */
class OrgAccess(private val orgs: OrgRepository) {
    suspend fun role(orgId: UUID, userId: UUID): Role? = orgs.role(orgId, userId)

    /** The caller's role, which must be at least [required]; non-members get "not found". */
    suspend fun require(orgId: UUID, userId: UUID, required: Role): Role {
        val role = orgs.role(orgId, userId) ?: throw DomainException.NotFound("Organization not found")
        if (!role.atLeast(required)) throw DomainException.Forbidden("This needs the ${required.name.lowercase()} role; you are ${role.name.lowercase()}")
        return role
    }
}

/** Audit trail of organization actions. Recording never fails the action itself. */
class AuditService(private val repo: AuditRepository, private val access: OrgAccess, private val clock: Clock = Clock.systemUTC()) : AuditLog {
    private val log = LoggerFactory.getLogger(AuditService::class.java)

    override suspend fun record(orgId: UUID, actor: Actor, action: String, targetType: String, targetId: String?, details: Map<String, String>) {
        runCatching { repo.record(orgId, actor, action, targetType, targetId, details.mapValues { it.value.take(500) }, clock.instant()) }
            .onFailure { log.warn("Could not write audit entry {} for {}", action, orgId, it) }
    }

    suspend fun list(userId: UUID, orgId: UUID, action: String?, beforeId: Long?, limit: Int, from: Instant? = null, to: Instant? = null): List<AuditEntry> {
        access.require(orgId, userId, Role.ADMIN)
        checkRange(from, to)
        return repo.list(orgId, action?.takeIf { it.isNotBlank() }, beforeId, limit.coerceIn(1, 200), from, to)
    }

    /** The audit log (newest first, at most [MAX_EXPORT] entries) as CSV for spreadsheets and compliance reviews. */
    suspend fun exportCsv(userId: UUID, orgId: UUID, action: String?, from: Instant?, to: Instant?): String {
        access.require(orgId, userId, Role.ADMIN)
        checkRange(from, to)
        val rows = mutableListOf<AuditEntry>()
        var before: Long? = null
        while (rows.size < MAX_EXPORT) {
            val page = repo.list(orgId, action?.takeIf { it.isNotBlank() }, before, PAGE, from, to)
            rows += page
            if (page.size < PAGE) break
            before = page.last().id
        }
        audit(orgId, userId, rows.size)
        return AuditCsv.write(rows.take(MAX_EXPORT))
    }

    private suspend fun audit(orgId: UUID, userId: UUID, count: Int) =
        record(orgId, Actor(userId), "audit.exported", "audit", null, mapOf("entries" to count.toString()))

    private fun checkRange(from: Instant?, to: Instant?) {
        if (from != null && to != null && from.isAfter(to)) throw DomainException.Validation("The start date must be before the end date")
    }

    companion object {
        const val MAX_EXPORT = 10_000
        private const val PAGE = 200
    }
}

/** CSV of audit entries (RFC 4180 quoting; formulas neutralized for spreadsheet safety). */
object AuditCsv {
    private val header = listOf("id", "at", "action", "actor_email", "actor_user_id", "actor_api_key_id", "target_type", "target_id", "details")

    fun write(entries: List<AuditEntry>): String = buildString {
        appendLine(header.joinToString(","))
        entries.forEach { e ->
            val details = e.details.entries.sortedBy { it.key }.joinToString("; ") { "${it.key}=${it.value}" }
            appendLine(
                listOf(
                    e.id.toString(), e.at.toString(), e.action, e.actorEmail.orEmpty(), e.actorUserId?.toString().orEmpty(),
                    e.actorApiKeyId?.toString().orEmpty(), e.targetType, e.targetId.orEmpty(), details,
                ).joinToString(",") { cell(it) },
            )
        }
    }

    fun cell(value: String): String {
        // A leading =, +, - or @ would run as a formula in spreadsheet apps.
        val safe = if (value.firstOrNull() in setOf('=', '+', '-', '@')) "'$value" else value
        return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }
}

/** How much one organization may use (configurable per deployment). */
data class TenantLimits(val maxMembers: Int = 200, val maxApiKeys: Int = 25, val maxWebhooks: Int = WebhookService.MAX_PER_ORG)

/** Organizations, members and invitations. */
class OrgService(
    private val orgs: OrgRepository,
    private val users: UserRepository,
    private val access: OrgAccess,
    private val audit: AuditLog,
    private val clock: Clock = Clock.systemUTC(),
    private val invitationTtl: Duration = Duration.ofDays(7),
    /** Called when a user joins or leaves an organization (their cached flow matches change). */
    private val membershipChanged: suspend (UUID) -> Unit = {},
    private val limits: TenantLimits = TenantLimits(),
) {
    /** What the organization uses and its limits (any member may look). */
    suspend fun usage(userId: UUID, orgId: UUID): Pair<com.voicecontrol.domain.org.OrgUsage, TenantLimits> {
        access.require(orgId, userId, Role.VIEWER)
        return orgs.usage(orgId) to limits
    }

    private suspend fun checkSeats(orgId: UUID, adding: Int) {
        val usage = orgs.usage(orgId)
        if (usage.members + usage.pendingInvitations + adding > limits.maxMembers) {
            throw DomainException.Validation("This organization can have at most ${limits.maxMembers} members (including open invitations)")
        }
    }

    suspend fun create(userId: UUID, name: String): Organization {
        val org = orgs.create(Organization(UUID.randomUUID(), validName(name), userId, clock.instant()), userId)
        audit.record(org.id, Actor(userId), "org.created", "organization", org.id.toString(), mapOf("name" to org.name))
        return org
    }

    suspend fun list(userId: UUID): List<OrgWithRole> = orgs.listForUser(userId)

    suspend fun rename(userId: UUID, orgId: UUID, name: String) {
        access.require(orgId, userId, Role.ADMIN)
        orgs.rename(orgId, validName(name))
        audit.record(orgId, Actor(userId), "org.renamed", "organization", orgId.toString(), mapOf("name" to name.trim()))
    }

    suspend fun delete(userId: UUID, orgId: UUID) {
        access.require(orgId, userId, Role.ADMIN)
        val members = orgs.members(orgId)
        if (!orgs.delete(orgId)) throw DomainException.NotFound("Organization not found")
        members.forEach { membershipChanged(it.userId) }
    }

    suspend fun members(userId: UUID, orgId: UUID): List<Member> {
        access.require(orgId, userId, Role.VIEWER)
        return orgs.members(orgId)
    }

    suspend fun setRole(userId: UUID, orgId: UUID, memberId: UUID, role: Role) {
        access.require(orgId, userId, Role.ADMIN)
        val current = orgs.role(orgId, memberId) ?: throw DomainException.NotFound("Member not found")
        if (current == Role.ADMIN && role != Role.ADMIN && orgs.adminCount(orgId) <= 1) {
            throw DomainException.Validation("An organization needs at least one admin")
        }
        orgs.setRole(orgId, memberId, role)
        audit.record(orgId, Actor(userId), "member.role_changed", "member", memberId.toString(), mapOf("from" to current.name, "to" to role.name))
    }

    /** Admins remove members; anyone can leave. The last admin can't leave or be removed. */
    suspend fun removeMember(userId: UUID, orgId: UUID, memberId: UUID) {
        if (memberId != userId) access.require(orgId, userId, Role.ADMIN) else access.require(orgId, userId, Role.VIEWER)
        val role = orgs.role(orgId, memberId) ?: throw DomainException.NotFound("Member not found")
        if (role == Role.ADMIN && orgs.adminCount(orgId) <= 1) throw DomainException.Validation("An organization needs at least one admin")
        orgs.removeMember(orgId, memberId)
        membershipChanged(memberId)
        audit.record(orgId, Actor(userId), if (memberId == userId) "member.left" else "member.removed", "member", memberId.toString(), emptyMap())
    }

    /** Creates an invitation and returns it with its one-time token (only its hash is stored). */
    suspend fun invite(userId: UUID, orgId: UUID, email: String, role: Role): Pair<Invitation, String> {
        access.require(orgId, userId, Role.ADMIN)
        val address = email.trim().lowercase()
        if (!Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(address) || address.length > 254) throw DomainException.Validation("Enter a valid email address")
        checkSeats(orgId, adding = 1)
        val token = Secrets.token()
        val now = clock.instant()
        val invitation = orgs.createInvitation(
            Invitation(UUID.randomUUID(), orgId, address, role, userId, now, now.plus(invitationTtl), null, null),
            Secrets.sha256(token),
        )
        audit.record(orgId, Actor(userId), "member.invited", "invitation", invitation.id.toString(), mapOf("email" to address, "role" to role.name))
        return invitation to token
    }

    suspend fun invitations(userId: UUID, orgId: UUID): List<Invitation> {
        access.require(orgId, userId, Role.ADMIN)
        return orgs.invitations(orgId)
    }

    suspend fun revokeInvitation(userId: UUID, orgId: UUID, id: UUID) {
        access.require(orgId, userId, Role.ADMIN)
        if (!orgs.revokeInvitation(orgId, id, clock.instant())) throw DomainException.NotFound("Invitation not found")
        audit.record(orgId, Actor(userId), "member.invitation_revoked", "invitation", id.toString(), emptyMap())
    }

    /** What an invitation link is for (org name, role, invited email), if it is still valid. */
    suspend fun preview(token: String): Pair<Invitation, Organization> {
        val invitation = valid(token)
        val org = orgs.find(invitation.orgId) ?: throw DomainException.NotFound("Invitation not found")
        return invitation to org
    }

    suspend fun accept(userId: UUID, token: String): Organization {
        val invitation = valid(token)
        val user = users.findById(userId) ?: throw DomainException.Unauthorized()
        if (!user.email.equals(invitation.email, ignoreCase = true)) {
            throw DomainException.Forbidden("This invitation is for ${invitation.email}. Sign in with that email to accept it.")
        }
        if (!orgs.accept(invitation, userId, clock.instant())) throw DomainException.NotFound("This invitation has expired or was already used")
        membershipChanged(userId)
        audit.record(invitation.orgId, Actor(userId), "member.joined", "member", userId.toString(), mapOf("role" to invitation.role.name))
        return orgs.find(invitation.orgId) ?: throw DomainException.NotFound("Organization not found")
    }

    private suspend fun valid(token: String): Invitation {
        val invitation = orgs.invitationByTokenHash(Secrets.sha256(token.trim())) ?: throw DomainException.NotFound("Invitation not found")
        if (invitation.revokedAt != null || invitation.acceptedAt != null || invitation.expiresAt.isBefore(clock.instant())) {
            throw DomainException.NotFound("This invitation has expired or was already used")
        }
        return invitation
    }

    private fun validName(name: String) = name.trim().takeIf { it.length in 2..80 } ?: throw DomainException.Validation("Name must be 2-80 characters")
}

/** API keys for third parties: org-scoped, limited by scopes and a per-key rate limit. */
class ApiKeyService(
    private val keys: ApiKeyRepository,
    private val access: OrgAccess,
    private val audit: AuditLog,
    private val clock: Clock = Clock.systemUTC(),
    private val limits: TenantLimits = TenantLimits(),
) {
    /**
     * Creates a key and returns it with its secret, which is shown once and never stored. With
     * [expiresInDays] the key stops working after that many days.
     */
    suspend fun create(
        userId: UUID,
        orgId: UUID,
        name: String,
        scopes: Set<ApiScope>,
        rateLimitPerMinute: Int,
        expiresInDays: Int? = null,
    ): Pair<ApiKey, String> {
        access.require(orgId, userId, Role.ADMIN)
        val n = name.trim().takeIf { it.length in 1..60 } ?: throw DomainException.Validation("Name must be 1-60 characters")
        if (scopes.isEmpty()) throw DomainException.Validation("Choose at least one scope")
        if (rateLimitPerMinute !in 1..10_000) throw DomainException.Validation("Rate limit must be 1-10000 requests per minute")
        if (expiresInDays != null && expiresInDays !in 1..MAX_EXPIRY_DAYS) throw DomainException.Validation("Keys can expire after 1 to $MAX_EXPIRY_DAYS days")
        val now = clock.instant()
        if (keys.list(orgId).count { it.revokedAt == null && !it.isExpired(now) } >= limits.maxApiKeys) {
            throw DomainException.Validation("This organization can have at most ${limits.maxApiKeys} active API keys; revoke one first")
        }
        val (prefix, secret) = newSecret()
        val key = keys.create(
            ApiKey(
                UUID.randomUUID(), orgId, n, prefix, scopes, rateLimitPerMinute, userId, now, null, null,
                expiresAt = expiresInDays?.let { now.plus(Duration.ofDays(it.toLong())) },
            ),
            Secrets.sha256(secret),
        )
        audit.record(
            orgId, Actor(userId), "api_key.created", "api_key", key.id.toString(),
            mapOf("name" to n, "scopes" to scopes.joinToString(",") { it.id }) + (key.expiresAt?.let { mapOf("expires_at" to it.toString()) } ?: emptyMap()),
        )
        return key to secret
    }

    /** Replaces a key's secret (same name, scopes and limits); the old secret stops working at once. */
    suspend fun rotate(userId: UUID, orgId: UUID, id: UUID): Pair<ApiKey, String> {
        access.require(orgId, userId, Role.ADMIN)
        val now = clock.instant()
        val current = keys.list(orgId).firstOrNull { it.id == id && it.revokedAt == null } ?: throw DomainException.NotFound("API key not found")
        if (current.isExpired(now)) throw DomainException.Validation("This key has expired; create a new one")
        val (prefix, secret) = newSecret()
        val rotated = keys.rotate(orgId, id, prefix, Secrets.sha256(secret), now) ?: throw DomainException.NotFound("API key not found")
        audit.record(orgId, Actor(userId), "api_key.rotated", "api_key", id.toString(), mapOf("name" to rotated.name, "old_prefix" to current.prefix))
        return rotated to secret
    }

    private fun newSecret(): Pair<String, String> {
        val prefix = PREFIX + Secrets.token(6).filter { it.isLetterOrDigit() }.take(8).lowercase()
        return prefix to prefix + "_" + Secrets.token(32)
    }

    suspend fun list(userId: UUID, orgId: UUID): List<ApiKey> {
        access.require(orgId, userId, Role.ADMIN)
        return keys.list(orgId)
    }

    suspend fun revoke(userId: UUID, orgId: UUID, id: UUID) {
        access.require(orgId, userId, Role.ADMIN)
        if (!keys.revoke(orgId, id, clock.instant())) throw DomainException.NotFound("API key not found")
        audit.record(orgId, Actor(userId), "api_key.revoked", "api_key", id.toString(), emptyMap())
    }

    /** The active key for a presented secret, or null. */
    suspend fun authenticate(secret: String): ApiKey? {
        if (!secret.startsWith(PREFIX)) return null
        val key = keys.findActiveByHash(Secrets.sha256(secret)) ?: return null
        val now = clock.instant()
        if (key.isExpired(now)) return null
        if (key.lastUsedAt == null || key.lastUsedAt!!.isBefore(now.minusSeconds(TOUCH_INTERVAL_SECONDS))) keys.touch(key.id, now)
        return key
    }

    companion object {
        const val PREFIX = "vck_"
        const val MAX_EXPIRY_DAYS = 365
        private const val TOUCH_INTERVAL_SECONDS = 60L
    }
}
