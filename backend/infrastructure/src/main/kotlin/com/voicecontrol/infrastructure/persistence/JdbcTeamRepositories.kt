package com.voicecontrol.infrastructure.persistence

import com.voicecontrol.domain.org.Actor
import com.voicecontrol.domain.org.ApiKey
import com.voicecontrol.domain.org.ApiKeyRepository
import com.voicecontrol.domain.org.ApiScope
import com.voicecontrol.domain.org.AttemptResult
import com.voicecontrol.domain.org.AuditEntry
import com.voicecontrol.domain.org.AuditRepository
import com.voicecontrol.domain.org.DeliveryStatus
import com.voicecontrol.domain.org.DueDelivery
import com.voicecontrol.domain.org.Invitation
import com.voicecontrol.domain.org.Member
import com.voicecontrol.domain.org.OrgRepository
import com.voicecontrol.domain.org.OrgUsage
import com.voicecontrol.domain.org.OrgWithRole
import com.voicecontrol.domain.org.Organization
import com.voicecontrol.domain.org.Role
import com.voicecontrol.domain.org.Webhook
import com.voicecontrol.domain.org.WebhookDelivery
import com.voicecontrol.domain.org.WebhookRepository
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

private fun Connection.textArray(values: Collection<String>) = createArrayOf("text", values.toTypedArray())
private fun ResultSet.textList(column: String): List<String> = (getArray(column)?.array as? Array<*>)?.map { it.toString() }.orEmpty()
private fun ResultSet.instantOrNull(column: String): Instant? = getTimestamp(column)?.toInstant()

class JdbcOrgRepository(private val db: Database) : OrgRepository {

    override suspend fun usage(orgId: UUID): OrgUsage = db.tx {
        query(
            """
            SELECT (SELECT count(*) FROM memberships WHERE org_id = ?) AS members,
                   (SELECT count(*) FROM invitations WHERE org_id = ? AND accepted_at IS NULL AND revoked_at IS NULL AND expires_at > now()) AS pending,
                   (SELECT count(*) FROM flows WHERE org_id = ? AND deleted_at IS NULL) AS flows,
                   (SELECT count(*) FROM api_keys WHERE org_id = ? AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at > now())) AS keys,
                   (SELECT count(*) FROM webhooks WHERE org_id = ?) AS webhooks,
                   (SELECT count(*) FROM run_requests r JOIN flows f ON f.id = r.flow_id
                     WHERE f.org_id = ? AND r.created_at > now() - interval '30 days') AS runs
            """.trimIndent(),
            orgId, orgId, orgId, orgId, orgId, orgId,
        ) { OrgUsage(it.getInt("members"), it.getInt("pending"), it.getInt("flows"), it.getInt("keys"), it.getInt("webhooks"), it.getInt("runs")) }.first()
    }

    override suspend fun create(org: Organization, adminUserId: UUID): Organization = db.tx {
        update("INSERT INTO organizations (id, name, created_by, created_at) VALUES (?, ?, ?, ?)", org.id, org.name, org.createdBy, org.createdAt)
        update("INSERT INTO memberships (org_id, user_id, role, created_at) VALUES (?, ?, 'ADMIN', ?)", org.id, adminUserId, org.createdAt)
        org
    }

    override suspend fun rename(orgId: UUID, name: String): Boolean = db.tx { update("UPDATE organizations SET name = ? WHERE id = ?", name, orgId) > 0 }

    override suspend fun delete(orgId: UUID): Boolean = db.tx { update("DELETE FROM organizations WHERE id = ?", orgId) > 0 }

    override suspend fun find(orgId: UUID): Organization? = db.tx {
        query("SELECT * FROM organizations WHERE id = ?", orgId, map = ::toOrg).firstOrNull()
    }

    override suspend fun listForUser(userId: UUID): List<OrgWithRole> = db.tx {
        query(
            """
            SELECT o.*, m.role, (SELECT count(*) FROM memberships x WHERE x.org_id = o.id) AS members
            FROM organizations o JOIN memberships m ON m.org_id = o.id WHERE m.user_id = ? ORDER BY lower(o.name)
            """.trimIndent(),
            userId,
        ) { OrgWithRole(toOrg(it), Role.valueOf(it.getString("role")), it.getInt("members")) }
    }

    override suspend fun role(orgId: UUID, userId: UUID): Role? = db.tx {
        query("SELECT role FROM memberships WHERE org_id = ? AND user_id = ?", orgId, userId) { Role.valueOf(it.getString("role")) }.firstOrNull()
    }

    override suspend fun members(orgId: UUID): List<Member> = db.tx {
        query(
            """
            SELECT m.*, u.email, u.name FROM memberships m JOIN users u ON u.id = m.user_id
            WHERE m.org_id = ? ORDER BY CASE m.role WHEN 'ADMIN' THEN 0 WHEN 'EDITOR' THEN 1 ELSE 2 END, lower(u.email)
            """.trimIndent(),
            orgId,
        ) { Member(it.uuid("org_id"), it.uuid("user_id"), it.getString("email"), it.getString("name"), Role.valueOf(it.getString("role")), it.instant("created_at")) }
    }

    override suspend fun setRole(orgId: UUID, userId: UUID, role: Role): Boolean = db.tx {
        update("UPDATE memberships SET role = ? WHERE org_id = ? AND user_id = ?", role.name, orgId, userId) > 0
    }

    override suspend fun removeMember(orgId: UUID, userId: UUID): Boolean = db.tx {
        update("DELETE FROM memberships WHERE org_id = ? AND user_id = ?", orgId, userId) > 0
    }

    override suspend fun adminCount(orgId: UUID): Int = db.tx {
        query("SELECT count(*) AS n FROM memberships WHERE org_id = ? AND role = 'ADMIN'", orgId) { it.getInt("n") }.first()
    }

    override suspend fun createInvitation(invitation: Invitation, tokenHash: String): Invitation = db.tx {
        update(
            "INSERT INTO invitations (id, org_id, email, role, token_hash, invited_by, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            invitation.id, invitation.orgId, invitation.email, invitation.role.name, tokenHash, invitation.invitedBy, invitation.createdAt, invitation.expiresAt,
        )
        invitation
    }

    override suspend fun invitations(orgId: UUID): List<Invitation> = db.tx {
        query(
            "SELECT * FROM invitations WHERE org_id = ? AND accepted_at IS NULL AND revoked_at IS NULL AND expires_at > now() ORDER BY created_at DESC",
            orgId, map = ::toInvitation,
        )
    }

    override suspend fun invitationByTokenHash(tokenHash: String): Invitation? = db.tx {
        query("SELECT * FROM invitations WHERE token_hash = ?", tokenHash, map = ::toInvitation).firstOrNull()
    }

    override suspend fun revokeInvitation(orgId: UUID, id: UUID, at: Instant): Boolean = db.tx {
        update("UPDATE invitations SET revoked_at = ? WHERE org_id = ? AND id = ? AND accepted_at IS NULL AND revoked_at IS NULL", at, orgId, id) > 0
    }

    override suspend fun accept(invitation: Invitation, userId: UUID, at: Instant): Boolean = db.tx {
        val marked = update(
            "UPDATE invitations SET accepted_at = ? WHERE id = ? AND accepted_at IS NULL AND revoked_at IS NULL AND expires_at > ?",
            at, invitation.id, at,
        ) > 0
        if (!marked) return@tx false
        // Joining again never lowers an existing role.
        update(
            """
            INSERT INTO memberships (org_id, user_id, role, created_at) VALUES (?, ?, ?, ?)
            ON CONFLICT (org_id, user_id) DO UPDATE SET role = CASE
                WHEN memberships.role = 'ADMIN' OR excluded.role = 'ADMIN' THEN 'ADMIN'
                WHEN memberships.role = 'EDITOR' OR excluded.role = 'EDITOR' THEN 'EDITOR'
                ELSE 'VIEWER' END
            """.trimIndent(),
            invitation.orgId, userId, invitation.role.name, at,
        )
        true
    }

    private fun toOrg(rs: ResultSet) = Organization(rs.uuid("id"), rs.getString("name"), rs.getObject("created_by", UUID::class.java), rs.instant("created_at"))

    private fun toInvitation(rs: ResultSet) = Invitation(
        id = rs.uuid("id"),
        orgId = rs.uuid("org_id"),
        email = rs.getString("email"),
        role = Role.valueOf(rs.getString("role")),
        invitedBy = rs.getObject("invited_by", UUID::class.java),
        createdAt = rs.instant("created_at"),
        expiresAt = rs.instant("expires_at"),
        acceptedAt = rs.instantOrNull("accepted_at"),
        revokedAt = rs.instantOrNull("revoked_at"),
    )
}

class JdbcAuditRepository(private val db: Database) : AuditRepository {
    private val json = Json
    private val detailsSerializer = MapSerializer(String.serializer(), String.serializer())

    override suspend fun record(orgId: UUID, actor: Actor, action: String, targetType: String, targetId: String?, details: Map<String, String>, at: Instant) {
        db.tx {
            update(
                "INSERT INTO audit_logs (org_id, actor_user_id, actor_api_key_id, action, target_type, target_id, details, at) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)",
                orgId, actor.userId, actor.apiKeyId, action, targetType, targetId, json.encodeToString(detailsSerializer, details), at,
            )
        }
    }

    override suspend fun list(orgId: UUID, action: String?, beforeId: Long?, limit: Int, from: Instant?, to: Instant?): List<AuditEntry> = db.tx {
        query(
            """
            SELECT a.*, u.email FROM audit_logs a LEFT JOIN users u ON u.id = a.actor_user_id
            WHERE a.org_id = ? AND (?::text IS NULL OR a.action = ? OR a.action LIKE ? || '.%') AND (?::bigint IS NULL OR a.id < ?)
              AND (?::timestamptz IS NULL OR a.at >= ?) AND (?::timestamptz IS NULL OR a.at < ?)
            ORDER BY a.id DESC LIMIT ?
            """.trimIndent(),
            orgId, action, action, action, beforeId, beforeId, from, from, to, to, limit,
        ) {
            AuditEntry(
                id = it.getLong("id"),
                orgId = it.uuid("org_id"),
                actorUserId = it.getObject("actor_user_id", UUID::class.java),
                actorEmail = it.getString("email"),
                actorApiKeyId = it.getObject("actor_api_key_id", UUID::class.java),
                action = it.getString("action"),
                targetType = it.getString("target_type"),
                targetId = it.getString("target_id"),
                details = json.decodeFromString(detailsSerializer, it.getString("details")),
                at = it.instant("at"),
            )
        }
    }
}

class JdbcApiKeyRepository(private val db: Database) : ApiKeyRepository {

    override suspend fun create(key: ApiKey, secretHash: String): ApiKey = db.tx {
        update(
            """
            INSERT INTO api_keys (id, org_id, name, prefix, key_hash, scopes, rate_limit_per_minute, created_by, created_at, expires_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            key.id, key.orgId, key.name, key.prefix, secretHash, textArray(key.scopes.map { it.id }), key.rateLimitPerMinute, key.createdBy, key.createdAt,
            key.expiresAt,
        )
        key
    }

    override suspend fun findActiveByHash(secretHash: String): ApiKey? = db.tx {
        query("SELECT * FROM api_keys WHERE key_hash = ? AND revoked_at IS NULL", secretHash, map = ::toKey).firstOrNull()
    }

    override suspend fun list(orgId: UUID): List<ApiKey> = db.tx {
        query("SELECT * FROM api_keys WHERE org_id = ? ORDER BY revoked_at IS NOT NULL, created_at DESC", orgId, map = ::toKey)
    }

    override suspend fun revoke(orgId: UUID, id: UUID, at: Instant): Boolean = db.tx {
        update("UPDATE api_keys SET revoked_at = ? WHERE org_id = ? AND id = ? AND revoked_at IS NULL", at, orgId, id) > 0
    }

    override suspend fun touch(id: UUID, at: Instant) {
        db.tx { update("UPDATE api_keys SET last_used_at = ? WHERE id = ?", at, id) }
    }

    override suspend fun rotate(orgId: UUID, id: UUID, prefix: String, secretHash: String, at: Instant): ApiKey? = db.tx {
        query(
            "UPDATE api_keys SET prefix = ?, key_hash = ?, rotated_at = ? WHERE org_id = ? AND id = ? AND revoked_at IS NULL RETURNING *",
            prefix, secretHash, at, orgId, id, map = ::toKey,
        ).firstOrNull()
    }

    private fun toKey(rs: ResultSet) = ApiKey(
        id = rs.uuid("id"),
        orgId = rs.uuid("org_id"),
        name = rs.getString("name"),
        prefix = rs.getString("prefix"),
        scopes = rs.textList("scopes").mapNotNull(ApiScope::of).toSet(),
        rateLimitPerMinute = rs.getInt("rate_limit_per_minute"),
        createdBy = rs.getObject("created_by", UUID::class.java),
        createdAt = rs.instant("created_at"),
        lastUsedAt = rs.instantOrNull("last_used_at"),
        revokedAt = rs.instantOrNull("revoked_at"),
        expiresAt = rs.instantOrNull("expires_at"),
        rotatedAt = rs.instantOrNull("rotated_at"),
    )
}

class JdbcWebhookRepository(private val db: Database) : WebhookRepository {

    override suspend fun create(webhook: Webhook): Webhook = db.tx {
        update(
            "INSERT INTO webhooks (id, org_id, url, secret, events, active, created_by, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            webhook.id, webhook.orgId, webhook.url, webhook.secret, textArray(webhook.events), webhook.active, webhook.createdBy, webhook.createdAt,
        )
        webhook
    }

    override suspend fun update(webhook: Webhook): Webhook? = db.tx {
        query(
            "UPDATE webhooks SET url = ?, secret = ?, events = ?, active = ? WHERE org_id = ? AND id = ? RETURNING *",
            webhook.url, webhook.secret, textArray(webhook.events), webhook.active, webhook.orgId, webhook.id, map = ::toWebhook,
        ).firstOrNull()
    }

    override suspend fun find(orgId: UUID, id: UUID): Webhook? = db.tx {
        query("SELECT * FROM webhooks WHERE org_id = ? AND id = ?", orgId, id, map = ::toWebhook).firstOrNull()
    }

    override suspend fun list(orgId: UUID): List<Webhook> = db.tx {
        query("SELECT * FROM webhooks WHERE org_id = ? ORDER BY created_at", orgId, map = ::toWebhook)
    }

    override suspend fun delete(orgId: UUID, id: UUID): Boolean = db.tx { update("DELETE FROM webhooks WHERE org_id = ? AND id = ?", orgId, id) > 0 }

    override suspend fun subscribers(orgId: UUID, eventType: String): List<Webhook> = db.tx {
        query("SELECT * FROM webhooks WHERE org_id = ? AND active AND ? = ANY(events)", orgId, eventType, map = ::toWebhook)
    }

    override suspend fun enqueue(delivery: WebhookDelivery): Boolean = db.tx {
        update(
            """
            INSERT INTO webhook_deliveries (id, webhook_id, event_id, event_type, payload, status, attempts, next_attempt_at, created_at)
            VALUES (?, ?, ?, ?, ?::jsonb, 'PENDING', 0, ?, ?) ON CONFLICT (webhook_id, event_id) DO NOTHING
            """.trimIndent(),
            delivery.id, delivery.webhookId, delivery.eventId, delivery.eventType, delivery.payload, delivery.nextAttemptAt, delivery.createdAt,
        ) > 0
    }

    override suspend fun deliveries(orgId: UUID, webhookId: UUID, limit: Int): List<WebhookDelivery> = db.tx {
        query(
            """
            SELECT d.* FROM webhook_deliveries d JOIN webhooks w ON w.id = d.webhook_id
            WHERE w.org_id = ? AND d.webhook_id = ? ORDER BY d.created_at DESC LIMIT ?
            """.trimIndent(),
            orgId, webhookId, limit, map = ::toDelivery,
        )
    }

    override suspend fun delivery(orgId: UUID, id: UUID): WebhookDelivery? = db.tx {
        query(
            "SELECT d.* FROM webhook_deliveries d JOIN webhooks w ON w.id = d.webhook_id WHERE w.org_id = ? AND d.id = ?",
            orgId, id, map = ::toDelivery,
        ).firstOrNull()
    }

    override suspend fun retry(orgId: UUID, id: UUID, at: Instant): Boolean = db.tx {
        update(
            """
            UPDATE webhook_deliveries d SET status = 'PENDING', next_attempt_at = ?
            FROM webhooks w WHERE w.id = d.webhook_id AND w.org_id = ? AND d.id = ?
            """.trimIndent(),
            at, orgId, id,
        ) > 0
    }

    /**
     * Claims due deliveries in one transaction (rows stay locked while they are sent, so other replicas
     * skip them), then stores each outcome. A failed attempt is rescheduled per [nextAttempt] or marked failed.
     */
    override suspend fun processDue(now: Instant, limit: Int, send: suspend (DueDelivery) -> AttemptResult, nextAttempt: (Int) -> Instant?): Int {
        val connection = db.dataSource.connection
        return try {
            connection.autoCommit = false
            val due = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                connection.query(
                    """
                    SELECT d.*, w.url, w.secret FROM webhook_deliveries d JOIN webhooks w ON w.id = d.webhook_id
                    WHERE d.status = 'PENDING' AND d.next_attempt_at <= ?
                    ORDER BY d.next_attempt_at LIMIT ? FOR UPDATE OF d SKIP LOCKED
                    """.trimIndent(),
                    now, limit,
                ) { DueDelivery(toDelivery(it), it.getString("url"), it.getString("secret")) }
            }
            for (item in due) {
                val result = send(item)
                val attempts = item.delivery.attempts + 1
                val (status, next) = when {
                    result.ok -> DeliveryStatus.SUCCEEDED to item.delivery.nextAttemptAt
                    else -> nextAttempt(attempts)?.let { DeliveryStatus.PENDING to it } ?: (DeliveryStatus.FAILED to item.delivery.nextAttemptAt)
                }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    connection.update(
                        """
                        UPDATE webhook_deliveries SET status = ?, attempts = ?, next_attempt_at = ?, last_status_code = ?, last_error = ?,
                            delivered_at = CASE WHEN ? THEN ? ELSE delivered_at END
                        WHERE id = ?
                        """.trimIndent(),
                        status.name, attempts, next, result.statusCode, result.error?.take(500), result.ok, Instant.now(), item.delivery.id,
                    )
                }
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { connection.commit() }
            due.size
        } catch (e: Throwable) {
            runCatching { connection.rollback() }
            throw e
        } finally {
            connection.close()
        }
    }

    private fun toWebhook(rs: ResultSet) = Webhook(
        id = rs.uuid("id"),
        orgId = rs.uuid("org_id"),
        url = rs.getString("url"),
        secret = rs.getString("secret"),
        events = rs.textList("events").toSet(),
        active = rs.getBoolean("active"),
        createdBy = rs.getObject("created_by", UUID::class.java),
        createdAt = rs.instant("created_at"),
    )

    private fun toDelivery(rs: ResultSet) = WebhookDelivery(
        id = rs.uuid("id"),
        webhookId = rs.uuid("webhook_id"),
        eventId = rs.getString("event_id"),
        eventType = rs.getString("event_type"),
        payload = rs.getString("payload"),
        status = DeliveryStatus.valueOf(rs.getString("status")),
        attempts = rs.getInt("attempts"),
        nextAttemptAt = rs.instant("next_attempt_at"),
        lastStatusCode = rs.getObject("last_status_code") as Int?,
        lastError = rs.getString("last_error"),
        createdAt = rs.instant("created_at"),
        deliveredAt = rs.instantOrNull("delivered_at"),
    )
}
