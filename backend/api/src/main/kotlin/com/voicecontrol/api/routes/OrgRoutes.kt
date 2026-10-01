package com.voicecontrol.api.routes

import com.voicecontrol.api.plugins.JWT_AUTH
import com.voicecontrol.api.plugins.userId
import com.voicecontrol.application.org.ApiKeyService
import com.voicecontrol.application.org.AuditService
import com.voicecontrol.application.org.OrgService
import com.voicecontrol.application.org.WebhookService
import com.voicecontrol.domain.common.DomainException
import com.voicecontrol.domain.org.ApiKey
import com.voicecontrol.domain.org.ApiScope
import com.voicecontrol.domain.org.AuditEntry
import com.voicecontrol.domain.org.Invitation
import com.voicecontrol.domain.org.Member
import com.voicecontrol.domain.org.OrgWithRole
import com.voicecontrol.domain.org.Organization
import com.voicecontrol.domain.org.Role
import com.voicecontrol.domain.org.Webhook
import com.voicecontrol.domain.org.WebhookDelivery
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.withCharset
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

@Serializable data class CreateOrgRequest(val name: String)
@Serializable data class OrgDto(val id: String, val name: String, val role: Role, val memberCount: Int, val createdAt: String) {
    companion object {
        fun from(o: OrgWithRole) = OrgDto(o.org.id.toString(), o.org.name, o.role, o.memberCount, o.org.createdAt.toString())
        fun from(o: Organization, role: Role, members: Int) = OrgDto(o.id.toString(), o.name, role, members, o.createdAt.toString())
    }
}
@Serializable data class MemberDto(val userId: String, val email: String, val name: String? = null, val role: Role, val joinedAt: String) {
    companion object {
        fun from(m: Member) = MemberDto(m.userId.toString(), m.email, m.name, m.role, m.joinedAt.toString())
    }
}
@Serializable data class SetRoleRequest(val role: Role)
@Serializable data class InviteRequest(val email: String, val role: Role = Role.EDITOR)
@Serializable data class InvitationDto(
    val id: String,
    val email: String,
    val role: Role,
    val createdAt: String,
    val expiresAt: String,
    /** Only returned when the invitation is created: the link token, shown once. */
    val token: String? = null,
) {
    companion object {
        fun from(i: Invitation, token: String? = null) = InvitationDto(i.id.toString(), i.email, i.role, i.createdAt.toString(), i.expiresAt.toString(), token)
    }
}
@Serializable data class InvitationPreviewDto(val orgName: String, val email: String, val role: Role, val expiresAt: String)

@Serializable data class CreateApiKeyRequest(val name: String, val scopes: List<String>, val rateLimitPerMinute: Int = 60, val expiresInDays: Int? = null)
@Serializable data class ApiKeyDto(
    val id: String,
    val name: String,
    val prefix: String,
    val scopes: List<String>,
    val rateLimitPerMinute: Int,
    val createdAt: String,
    val lastUsedAt: String? = null,
    val revokedAt: String? = null,
    /** Only returned when the key is created or rotated; never stored. */
    val secret: String? = null,
    val expiresAt: String? = null,
    val expired: Boolean = false,
    val rotatedAt: String? = null,
) {
    companion object {
        fun from(k: ApiKey, secret: String? = null) = ApiKeyDto(
            k.id.toString(), k.name, k.prefix, k.scopes.map { it.id }.sorted(), k.rateLimitPerMinute, k.createdAt.toString(),
            k.lastUsedAt?.toString(), k.revokedAt?.toString(), secret, k.expiresAt?.toString(), k.isExpired(java.time.Instant.now()), k.rotatedAt?.toString(),
        )
    }
}

@Serializable data class OrgUsageDto(
    val members: Int,
    val pendingInvitations: Int,
    val flows: Int,
    val activeApiKeys: Int,
    val webhooks: Int,
    val runs30d: Int,
    val maxMembers: Int,
    val maxApiKeys: Int,
    val maxWebhooks: Int,
)

@Serializable data class WebhookRequest(val url: String? = null, val events: List<String>? = null, val active: Boolean? = null)
@Serializable data class WebhookDto(
    val id: String,
    val url: String,
    val events: List<String>,
    val active: Boolean,
    val createdAt: String,
    /** Signing secret: returned when created or rotated. */
    val secret: String? = null,
) {
    companion object {
        fun from(w: Webhook, withSecret: Boolean = false) =
            WebhookDto(w.id.toString(), w.url, w.events.sorted(), w.active, w.createdAt.toString(), if (withSecret) w.secret else null)
    }
}
@Serializable data class DeliveryDto(
    val id: String,
    val eventId: String,
    val eventType: String,
    val status: String,
    val attempts: Int,
    val nextAttemptAt: String,
    val lastStatusCode: Int? = null,
    val lastError: String? = null,
    val createdAt: String,
    val deliveredAt: String? = null,
    val payload: String,
) {
    companion object {
        fun from(d: WebhookDelivery) = DeliveryDto(
            d.id.toString(), d.eventId, d.eventType, d.status.name, d.attempts, d.nextAttemptAt.toString(), d.lastStatusCode, d.lastError,
            d.createdAt.toString(), d.deliveredAt?.toString(), d.payload,
        )
    }
}

@Serializable data class AuditEntryDto(
    val id: Long,
    val action: String,
    val targetType: String,
    val targetId: String? = null,
    val actorUserId: String? = null,
    val actorEmail: String? = null,
    val actorApiKeyId: String? = null,
    val details: Map<String, String>,
    val at: String,
) {
    companion object {
        fun from(a: AuditEntry) = AuditEntryDto(
            a.id, a.action, a.targetType, a.targetId, a.actorUserId?.toString(), a.actorEmail, a.actorApiKeyId?.toString(), a.details, a.at.toString(),
        )
    }
}

/** "2026-10-01" (start of that day, or its end with [endOfDay]) or a full ISO instant. */
private fun instantParam(value: String?, name: String, endOfDay: Boolean = false): Instant? {
    val v = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return runCatching { Instant.parse(v) }.getOrNull()
        ?: runCatching { LocalDate.parse(v).let { if (endOfDay) it.plusDays(1) else it }.atStartOfDay(ZoneOffset.UTC).toInstant() }.getOrNull()
        ?: throw DomainException.Validation("$name must be a date like 2026-10-01")
}

private fun ApplicationCall.uuid(name: String): UUID =
    runCatching { UUID.fromString(parameters[name]) }.getOrElse { throw DomainException.NotFound("Not found") }

fun Route.orgRoutes(orgs: OrgService, keys: ApiKeyService, webhooks: WebhookService, audit: AuditService) {
    authenticate(JWT_AUTH) {
        route("/v1/orgs") {
            get { call.respond(orgs.list(call.userId).map(OrgDto::from)) }
            post {
                val org = orgs.create(call.userId, call.receive<CreateOrgRequest>().name)
                call.respond(HttpStatusCode.Created, OrgDto.from(org, Role.ADMIN, 1))
            }
            route("/{orgId}") {
                patch {
                    orgs.rename(call.userId, call.uuid("orgId"), call.receive<CreateOrgRequest>().name)
                    call.respond(HttpStatusCode.NoContent)
                }
                delete {
                    orgs.delete(call.userId, call.uuid("orgId"))
                    call.respond(HttpStatusCode.NoContent)
                }

                route("/members") {
                    get { call.respond(orgs.members(call.userId, call.uuid("orgId")).map(MemberDto::from)) }
                    patch("/{userId}") {
                        orgs.setRole(call.userId, call.uuid("orgId"), call.uuid("userId"), call.receive<SetRoleRequest>().role)
                        call.respond(HttpStatusCode.NoContent)
                    }
                    delete("/{userId}") {
                        orgs.removeMember(call.userId, call.uuid("orgId"), call.uuid("userId"))
                        call.respond(HttpStatusCode.NoContent)
                    }
                }

                route("/invitations") {
                    get { call.respond(orgs.invitations(call.userId, call.uuid("orgId")).map { InvitationDto.from(it) }) }
                    post {
                        val body = call.receive<InviteRequest>()
                        val (invitation, token) = orgs.invite(call.userId, call.uuid("orgId"), body.email, body.role)
                        call.respond(HttpStatusCode.Created, InvitationDto.from(invitation, token))
                    }
                    delete("/{id}") {
                        orgs.revokeInvitation(call.userId, call.uuid("orgId"), call.uuid("id"))
                        call.respond(HttpStatusCode.NoContent)
                    }
                }

                route("/api-keys") {
                    get { call.respond(keys.list(call.userId, call.uuid("orgId")).map { ApiKeyDto.from(it) }) }
                    post {
                        val body = call.receive<CreateApiKeyRequest>()
                        val scopes = body.scopes.map { ApiScope.of(it) ?: throw DomainException.Validation("Unknown scope '$it'") }.toSet()
                        val (key, secret) = keys.create(call.userId, call.uuid("orgId"), body.name, scopes, body.rateLimitPerMinute, body.expiresInDays)
                        call.respond(HttpStatusCode.Created, ApiKeyDto.from(key, secret))
                    }
                    post("/{id}/rotate") {
                        val (key, secret) = keys.rotate(call.userId, call.uuid("orgId"), call.uuid("id"))
                        call.respond(ApiKeyDto.from(key, secret))
                    }
                    delete("/{id}") {
                        keys.revoke(call.userId, call.uuid("orgId"), call.uuid("id"))
                        call.respond(HttpStatusCode.NoContent)
                    }
                }

                route("/webhooks") {
                    get { call.respond(webhooks.list(call.userId, call.uuid("orgId")).map { WebhookDto.from(it) }) }
                    post {
                        val body = call.receive<WebhookRequest>()
                        val hook = webhooks.create(
                            call.userId, call.uuid("orgId"), body.url ?: throw DomainException.Validation("url is required"), body.events.orEmpty().toSet(),
                        )
                        call.respond(HttpStatusCode.Created, WebhookDto.from(hook, withSecret = true))
                    }
                    route("/{id}") {
                        patch {
                            val body = call.receive<WebhookRequest>()
                            call.respond(WebhookDto.from(webhooks.update(call.userId, call.uuid("orgId"), call.uuid("id"), body.url, body.events?.toSet(), body.active)))
                        }
                        delete {
                            webhooks.delete(call.userId, call.uuid("orgId"), call.uuid("id"))
                            call.respond(HttpStatusCode.NoContent)
                        }
                        post("/rotate-secret") {
                            call.respond(WebhookDto.from(webhooks.rotateSecret(call.userId, call.uuid("orgId"), call.uuid("id")), withSecret = true))
                        }
                        post("/ping") {
                            call.respond(HttpStatusCode.Accepted, DeliveryDto.from(webhooks.ping(call.userId, call.uuid("orgId"), call.uuid("id"))))
                        }
                        get("/deliveries") {
                            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
                            call.respond(webhooks.deliveries(call.userId, call.uuid("orgId"), call.uuid("id"), limit).map(DeliveryDto::from))
                        }
                    }
                    post("/deliveries/{deliveryId}/redeliver") {
                        call.respond(DeliveryDto.from(webhooks.redeliver(call.userId, call.uuid("orgId"), call.uuid("deliveryId"))))
                    }
                }

                get("/audit") {
                    val q = call.request.queryParameters
                    val entries = audit.list(
                        call.userId, call.uuid("orgId"), q["action"], q["before"]?.toLongOrNull(), q["limit"]?.toIntOrNull() ?: 50,
                        instantParam(q["from"], "from"), instantParam(q["to"], "to", endOfDay = true),
                    )
                    call.respond(entries.map(AuditEntryDto::from))
                }
                get("/audit/export") {
                    val q = call.request.queryParameters
                    val csv = audit.exportCsv(call.userId, call.uuid("orgId"), q["action"], instantParam(q["from"], "from"), instantParam(q["to"], "to", endOfDay = true))
                    call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"audit-log.csv\"")
                    call.respondText(csv, ContentType.Text.CSV.withCharset(Charsets.UTF_8))
                }
                get("/usage") {
                    val (u, limits) = orgs.usage(call.userId, call.uuid("orgId"))
                    call.respond(
                        OrgUsageDto(u.members, u.pendingInvitations, u.flows, u.activeApiKeys, u.webhooks, u.runs30d, limits.maxMembers, limits.maxApiKeys, limits.maxWebhooks),
                    )
                }
            }
        }

        route("/v1/invitations/{token}") {
            get {
                val (invitation, org) = orgs.preview(call.parameters["token"].orEmpty())
                call.respond(InvitationPreviewDto(org.name, invitation.email, invitation.role, invitation.expiresAt.toString()))
            }
            post("/accept") {
                val org = orgs.accept(call.userId, call.parameters["token"].orEmpty())
                val mine = orgs.list(call.userId).firstOrNull { it.org.id == org.id }
                call.respond(if (mine != null) OrgDto.from(mine) else OrgDto.from(org, Role.VIEWER, 0))
            }
        }
    }
}
