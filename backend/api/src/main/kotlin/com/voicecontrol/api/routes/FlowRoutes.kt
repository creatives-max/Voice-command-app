package com.voicecontrol.api.routes

import com.voicecontrol.api.plugins.JWT_AUTH
import com.voicecontrol.api.plugins.userId
import com.voicecontrol.application.flow.FlowService
import com.voicecontrol.application.flow.NewFlow
import com.voicecontrol.application.match.FlowMatchService
import com.voicecontrol.domain.common.DomainException
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

fun Route.flowRoutes(flows: FlowService, matcher: FlowMatchService) {
    authenticate(JWT_AUTH) {
        route("/v1/flows") {
            get {
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
                val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
                val items = flows.list(call.userId, call.request.queryParameters["appPackage"], limit, offset).map(FlowSummaryDto::from)
                call.respond(Page(items, limit, offset))
            }
            post {
                val body = call.receive<CreateFlowRequest>()
                val saved = flows.createFromDevice(call.userId, NewFlow(body.appPackage, body.name, body.screenSignature, body.steps))
                call.respond(HttpStatusCode.Created, FlowDto.from(saved))
            }
            get("/apps") { call.respond(flows.apps(call.userId).map(AppSummaryDto::from)) }
            post("/match") {
                val body = call.receive<MatchRequest>()
                if (body.signature.isBlank() || body.signature.length > FlowService.MAX_SIGNATURE) throw DomainException.Validation("Invalid signature")
                val match = matcher.match(call.userId, FlowService.validatePackage(body.appPackage), body.signature)
                call.respond(
                    if (match == null) MatchResponse() else MatchResponse(FlowDto.from(match.flow), match.kind.name.lowercase(), match.similarity),
                )
            }
            route("/{id}") {
                get { call.respond(FlowDto.from(flows.get(call.userId, call.flowId()))) }
                put {
                    val body = call.receive<UpdateFlowRequest>()
                    call.respond(FlowDto.from(flows.update(call.userId, call.flowId(), body.expectedVersion, body.name, body.steps, body.changeNote)))
                }
                delete {
                    flows.delete(call.userId, call.flowId())
                    call.respond(HttpStatusCode.NoContent)
                }
                get("/versions") { call.respond(flows.versions(call.userId, call.flowId()).map(FlowVersionDto::from)) }
                get("/versions/{version}") {
                    val version = call.parameters["version"]?.toIntOrNull() ?: throw DomainException.Validation("Invalid version")
                    call.respond(FlowVersionDto.from(flows.version(call.userId, call.flowId(), version)))
                }
                post("/rollback") {
                    val body = call.receive<RollbackRequest>()
                    call.respond(FlowDto.from(flows.rollback(call.userId, call.flowId(), body.version)))
                }
            }
        }
    }
}

private fun ApplicationCall.flowId(): UUID =
    parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: throw DomainException.NotFound("Flow not found")
