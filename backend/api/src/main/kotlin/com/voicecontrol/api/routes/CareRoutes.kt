package com.voicecontrol.api.routes

import com.voicecontrol.api.plugins.JWT_AUTH
import com.voicecontrol.api.plugins.ownUserId
import com.voicecontrol.application.care.CareService
import com.voicecontrol.domain.care.CareEvent
import com.voicecontrol.domain.care.CareLink
import com.voicecontrol.domain.care.CarePermission
import com.voicecontrol.domain.common.DomainException
import com.voicecontrol.domain.event.RateLimiter
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
import java.util.UUID

@Serializable data class CareInviteRequest(val permissions: List<String> = emptyList())
@Serializable data class CareAcceptRequest(val code: String)
@Serializable data class CarePermissionsRequest(val permissions: List<String>)

@Serializable data class CareLinkDto(
    val id: String,
    /** "receiver": you are being helped; "caregiver": you help the other person. */
    val role: String,
    val status: String,
    val permissions: List<String>,
    val otherEmail: String? = null,
    val otherName: String? = null,
    val createdAt: String,
    val acceptedAt: String? = null,
    /** Pending invites: when the code stops working. */
    val expiresAt: String? = null,
) {
    companion object {
        fun from(link: CareLink, me: UUID): CareLinkDto {
            val receiver = link.receiverId == me
            return CareLinkDto(
                link.id.toString(), if (receiver) "receiver" else "caregiver", link.status.name, link.permissions.map { it.id }.sorted(),
                if (receiver) link.caregiverEmail else link.receiverEmail, if (receiver) link.caregiverName else link.receiverName,
                link.createdAt.toString(), link.acceptedAt?.toString(), link.codeExpiresAt?.toString(),
            )
        }
    }
}

@Serializable data class CareInviteDto(val link: CareLinkDto, val code: String, val expiresAt: String)

@Serializable data class CareEventDto(val id: Long, val action: String, val actorEmail: String? = null, val details: Map<String, String>, val at: String) {
    companion object {
        fun from(e: CareEvent) = CareEventDto(e.id, e.action, e.actorEmail, e.details, e.at.toString())
    }
}

private fun permissions(ids: List<String>): Set<CarePermission> =
    ids.map { CarePermission.of(it) ?: throw DomainException.Validation("Unknown permission '$it'") }.toSet()

private fun ApplicationCall.linkId(): UUID =
    runCatching { UUID.fromString(parameters["id"]) }.getOrElse { throw DomainException.NotFound("Not found") }

/** Remote caregiver mode (people only, never API keys): invites, consent, permissions and the activity log. */
fun Route.careRoutes(care: CareService, rateLimiter: RateLimiter) {
    authenticate(JWT_AUTH) {
        route("/v1/care") {
            post("/invites") {
                val me = call.ownUserId
                val (link, code) = care.invite(me, permissions(call.receive<CareInviteRequest>().permissions))
                call.respond(HttpStatusCode.Created, CareInviteDto(CareLinkDto.from(link, me), code, link.codeExpiresAt.toString()))
            }
            post("/accept") {
                val me = call.ownUserId
                if (!rateLimiter.tryAcquire("care-accept:$me", ACCEPT_ATTEMPTS_PER_MINUTE, 60)) {
                    throw DomainException.RateLimited("Too many attempts, please wait a minute")
                }
                call.respond(CareLinkDto.from(care.accept(me, call.receive<CareAcceptRequest>().code), me))
            }
            route("/links") {
                get {
                    val me = call.ownUserId
                    call.respond(care.list(me).map { CareLinkDto.from(it, me) })
                }
                patch("/{id}") {
                    val me = call.ownUserId
                    call.respond(CareLinkDto.from(care.setPermissions(me, call.linkId(), permissions(call.receive<CarePermissionsRequest>().permissions)), me))
                }
                delete("/{id}") {
                    care.revoke(call.ownUserId, call.linkId())
                    call.respond(HttpStatusCode.NoContent)
                }
                get("/{id}/events") {
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
                    call.respond(care.events(call.ownUserId, call.linkId(), limit).map(CareEventDto::from))
                }
            }
        }
    }
}

private const val ACCEPT_ATTEMPTS_PER_MINUTE = 10
