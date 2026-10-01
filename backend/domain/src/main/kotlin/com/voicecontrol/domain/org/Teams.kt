package com.voicecontrol.domain.org

import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

/** Roles in an organization, from most to least powerful. */
@Serializable
enum class Role {
    /** Manage members, API keys, webhooks; delete and move flows. */
    ADMIN,
    /** Create and edit the organization's flows. */
    EDITOR,
    /** Read flows and run them on their own phones. */
    VIEWER;

    fun atLeast(required: Role): Boolean = ordinal <= required.ordinal
}

data class Organization(val id: UUID, val name: String, val createdBy: UUID?, val createdAt: Instant)

data class OrgWithRole(val org: Organization, val role: Role, val memberCount: Int)

data class Member(val orgId: UUID, val userId: UUID, val email: String, val name: String?, val role: Role, val joinedAt: Instant)

data class Invitation(
    val id: UUID,
    val orgId: UUID,
    val email: String,
    val role: Role,
    val invitedBy: UUID?,
    val createdAt: Instant,
    val expiresAt: Instant,
    val acceptedAt: Instant?,
    val revokedAt: Instant?,
)

interface OrgRepository {
    suspend fun create(org: Organization, adminUserId: UUID): Organization
    suspend fun rename(orgId: UUID, name: String): Boolean
    suspend fun delete(orgId: UUID): Boolean
    suspend fun find(orgId: UUID): Organization?
    suspend fun listForUser(userId: UUID): List<OrgWithRole>
    suspend fun role(orgId: UUID, userId: UUID): Role?
    suspend fun members(orgId: UUID): List<Member>
    suspend fun setRole(orgId: UUID, userId: UUID, role: Role): Boolean
    suspend fun removeMember(orgId: UUID, userId: UUID): Boolean
    suspend fun adminCount(orgId: UUID): Int
    suspend fun createInvitation(invitation: Invitation, tokenHash: String): Invitation
    suspend fun invitations(orgId: UUID): List<Invitation>
    suspend fun invitationByTokenHash(tokenHash: String): Invitation?
    suspend fun revokeInvitation(orgId: UUID, id: UUID, at: Instant): Boolean
    /** Marks the invitation accepted and adds the member (keeping a higher existing role). */
    suspend fun accept(invitation: Invitation, userId: UUID, at: Instant): Boolean
}

/** Who performed an action: a signed-in user, or a third party with an API key. */
data class Actor(val userId: UUID?, val apiKeyId: UUID? = null)

data class AuditEntry(
    val id: Long,
    val orgId: UUID,
    val actorUserId: UUID?,
    val actorEmail: String?,
    val actorApiKeyId: UUID?,
    val action: String,
    val targetType: String,
    val targetId: String?,
    val details: Map<String, String>,
    val at: Instant,
)

interface AuditRepository {
    suspend fun record(orgId: UUID, actor: Actor, action: String, targetType: String, targetId: String?, details: Map<String, String>, at: Instant)
    suspend fun list(orgId: UUID, action: String?, beforeId: Long?, limit: Int): List<AuditEntry>
}

/** Recording port used by services; implementations must never fail the caller's action. */
fun interface AuditLog {
    suspend fun record(orgId: UUID, actor: Actor, action: String, targetType: String, targetId: String?, details: Map<String, String>)
}

/** What an API key may do. */
@Serializable
enum class ApiScope(val id: String) {
    FLOWS_READ("flows:read"),
    FLOWS_WRITE("flows:write"),
    RUNS_WRITE("runs:write");

    companion object {
        fun of(id: String): ApiScope? = entries.firstOrNull { it.id == id }
    }
}

data class ApiKey(
    val id: UUID,
    val orgId: UUID,
    val name: String,
    /** Shown in lists so keys can be told apart ("vck_ab12cd34"). */
    val prefix: String,
    val scopes: Set<ApiScope>,
    val rateLimitPerMinute: Int,
    val createdBy: UUID?,
    val createdAt: Instant,
    val lastUsedAt: Instant?,
    val revokedAt: Instant?,
)

interface ApiKeyRepository {
    suspend fun create(key: ApiKey, secretHash: String): ApiKey
    suspend fun findActiveByHash(secretHash: String): ApiKey?
    suspend fun list(orgId: UUID): List<ApiKey>
    suspend fun revoke(orgId: UUID, id: UUID, at: Instant): Boolean
    suspend fun touch(id: UUID, at: Instant)
}

/** Events a webhook can subscribe to. */
object WebhookEvents {
    const val FLOW_VERSION_SAVED = "flow.version_saved"
    const val FLOW_DELETED = "flow.deleted"
    const val RUN_FINISHED = "run.finished"
    const val PING = "ping"
    val ALL = setOf(FLOW_VERSION_SAVED, FLOW_DELETED, RUN_FINISHED)
}

data class Webhook(
    val id: UUID,
    val orgId: UUID,
    val url: String,
    val secret: String,
    val events: Set<String>,
    val active: Boolean,
    val createdBy: UUID?,
    val createdAt: Instant,
)

enum class DeliveryStatus { PENDING, SUCCEEDED, FAILED }

data class WebhookDelivery(
    val id: UUID,
    val webhookId: UUID,
    val eventId: String,
    val eventType: String,
    val payload: String,
    val status: DeliveryStatus,
    val attempts: Int,
    val nextAttemptAt: Instant,
    val lastStatusCode: Int?,
    val lastError: String?,
    val createdAt: Instant,
    val deliveredAt: Instant?,
)

/** A claimed delivery together with where and how to send it. */
data class DueDelivery(val delivery: WebhookDelivery, val url: String, val secret: String)

/** Result of one delivery attempt. */
data class AttemptResult(val statusCode: Int?, val error: String?) {
    val ok: Boolean get() = statusCode != null && statusCode in 200..299
}

interface WebhookRepository {
    suspend fun create(webhook: Webhook): Webhook
    suspend fun update(webhook: Webhook): Webhook?
    suspend fun find(orgId: UUID, id: UUID): Webhook?
    suspend fun list(orgId: UUID): List<Webhook>
    suspend fun delete(orgId: UUID, id: UUID): Boolean
    suspend fun subscribers(orgId: UUID, eventType: String): List<Webhook>
    /** Inserts a pending delivery; ignored if this webhook already has this event (at-least-once events). */
    suspend fun enqueue(delivery: WebhookDelivery): Boolean
    suspend fun deliveries(orgId: UUID, webhookId: UUID, limit: Int): List<WebhookDelivery>
    suspend fun delivery(orgId: UUID, id: UUID): WebhookDelivery?
    suspend fun retry(orgId: UUID, id: UUID, at: Instant): Boolean
    /** Claims due deliveries (FOR UPDATE SKIP LOCKED), sends them with [send] and stores the outcome. */
    suspend fun processDue(now: Instant, limit: Int, send: suspend (DueDelivery) -> AttemptResult, nextAttempt: (Int) -> Instant?): Int
}
