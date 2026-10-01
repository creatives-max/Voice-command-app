package com.voicecontrol.api.routes

import com.voicecontrol.api.plugins.API_KEY_AUTH
import com.voicecontrol.api.plugins.JWT_AUTH
import com.voicecontrol.api.plugins.keyContext
import com.voicecontrol.api.plugins.orgContext
import com.voicecontrol.api.plugins.requireScope
import com.voicecontrol.api.plugins.requireUser
import com.voicecontrol.api.plugins.userId
import com.voicecontrol.application.flow.FlowService
import com.voicecontrol.application.flow.NewFlow
import com.voicecontrol.application.match.FlowMatchService
import com.voicecontrol.domain.common.DomainException
import com.voicecontrol.domain.org.ApiScope
import kotlinx.serialization.Serializable
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import java.util.UUID

/** Moves a flow to an organization, or back to the caller's personal flows when [orgId] is null. */
@Serializable data class TransferFlowRequest(val orgId: String? = null)

fun Route.flowRoutes(flows: FlowService, matcher: FlowMatchService) {
    // Signed-in people and organization API keys (flows:read / flows:write).
    authenticate(JWT_AUTH, API_KEY_AUTH) {
        route("/v1/flows") {
            get {
                call.requireScope(ApiScope.FLOWS_READ)
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
                val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
                val items = flows.list(call.userId, call.request.queryParameters["appPackage"], limit, offset, call.orgContext).map(FlowSummaryDto::from)
                call.respond(Page(items, limit, offset))
            }
            post {
                call.requireUser()
                val body = call.receive<CreateFlowRequest>()
                val saved = flows.createFromDevice(call.userId, NewFlow(body.appPackage, body.name, body.screenSignature, body.steps, taught = body.source == "RECORDED"))
                call.respond(HttpStatusCode.Created, FlowDto.from(saved))
            }
            get("/apps") {
                call.requireScope(ApiScope.FLOWS_READ)
                call.respond(flows.apps(call.userId, call.orgContext).map(AppSummaryDto::from))
            }
            post("/match") {
                call.requireUser()
                val body = call.receive<MatchRequest>()
                if (body.signature.isBlank() || body.signature.length > FlowService.MAX_SIGNATURE) throw DomainException.Validation("Invalid signature")
                val match = matcher.match(call.userId, FlowService.validatePackage(body.appPackage), body.signature)
                call.respond(
                    if (match == null) MatchResponse() else MatchResponse(FlowDto.from(match.flow), match.kind.name.lowercase(), match.similarity),
                )
            }
            route("/{id}") {
                get {
                    call.requireScope(ApiScope.FLOWS_READ)
                    call.respond(FlowDto.from(flows.get(call.userId, call.flowId(), call.keyContext)))
                }
                put {
                    call.requireScope(ApiScope.FLOWS_WRITE)
                    val body = call.receive<UpdateFlowRequest>()
                    call.respond(
                        FlowDto.from(flows.update(call.userId, call.flowId(), body.expectedVersion, body.name, body.steps, body.changeNote, key = call.keyContext)),
                    )
                }
                delete {
                    call.requireScope(ApiScope.FLOWS_WRITE)
                    flows.delete(call.userId, call.flowId(), call.keyContext)
                    call.respond(HttpStatusCode.NoContent)
                }
                get("/versions") {
                    call.requireScope(ApiScope.FLOWS_READ)
                    call.respond(flows.versions(call.userId, call.flowId(), call.keyContext).map(FlowVersionDto::from))
                }
                get("/versions/{version}") {
                    call.requireScope(ApiScope.FLOWS_READ)
                    val version = call.parameters["version"]?.toIntOrNull() ?: throw DomainException.Validation("Invalid version")
                    call.respond(FlowVersionDto.from(flows.version(call.userId, call.flowId(), version, call.keyContext)))
                }
                post("/rollback") {
                    call.requireScope(ApiScope.FLOWS_WRITE)
                    val body = call.receive<RollbackRequest>()
                    call.respond(FlowDto.from(flows.rollback(call.userId, call.flowId(), body.version, call.keyContext)))
                }
                post("/transfer") {
                    call.requireUser()
                    val body = call.receive<TransferFlowRequest>()
                    val target = body.orgId?.takeIf { it.isNotBlank() }?.let {
                        runCatching { UUID.fromString(it) }.getOrElse { throw DomainException.Validation("orgId must be an organization id") }
                    }
                    call.respond(FlowDto.from(flows.transfer(call.userId, call.flowId(), target)))
                }
            }
        }
    }
}

private fun ApplicationCall.flowId(): UUID =
    parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: throw DomainException.NotFound("Flow not found")
