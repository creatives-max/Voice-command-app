package com.voicecontrol.api.plugins

import com.voicecontrol.application.care.CareService
import com.voicecontrol.application.flow.FlowService
import com.voicecontrol.domain.care.CarePermission
import com.voicecontrol.domain.common.DomainException
import io.ktor.http.HttpMethod
import io.ktor.http.isSuccess
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingNode
import java.util.UUID

private const val ID = "[0-9a-fA-F-]{36}"

/** A request a caregiver may make for the person they help, what it needs, and how it is logged. */
private class CareRule(val method: HttpMethod, pattern: String, val needs: CarePermission? = null, val action: String? = null) {
    val regex = Regex(pattern)
}

/**
 * Everything a caregiver can do through a care link. Reading flows, triggers, phones and runs comes
 * with every link; the rest needs the permission the person granted. Anything not listed here is
 * refused while acting for someone (GET requests just answer for the caregiver themself).
 */
private val rules = listOf(
    CareRule(HttpMethod.Get, "^/v1/flows$"),
    CareRule(HttpMethod.Get, "^/v1/flows/apps$"),
    CareRule(HttpMethod.Get, "^/v1/flows/$ID$"),
    CareRule(HttpMethod.Get, "^/v1/flows/$ID/(versions|versions/[0-9]+|triggers|layout|analytics|comments)$"),
    CareRule(HttpMethod.Post, "^/v1/flows/$ID/dry-run$"),
    CareRule(HttpMethod.Get, "^/v1/analytics/flows$"),
    CareRule(HttpMethod.Get, "^/v1/devices$"),
    CareRule(HttpMethod.Get, "^/v1/run-requests$"),
    CareRule(HttpMethod.Get, "^/v1/run-requests/$ID(/events)?$"),
    CareRule(HttpMethod.Put, "^/v1/flows/$ID$", CarePermission.EDIT_FLOWS, "flow_edited"),
    CareRule(HttpMethod.Post, "^/v1/flows/$ID/rollback$", CarePermission.EDIT_FLOWS, "flow_restored"),
    CareRule(HttpMethod.Put, "^/v1/flows/$ID/layout$", CarePermission.EDIT_FLOWS),
    CareRule(HttpMethod.Post, "^/v1/flows/$ID/triggers$", CarePermission.EDIT_FLOWS, "trigger_added"),
    CareRule(HttpMethod.Put, "^/v1/triggers/$ID$", CarePermission.EDIT_FLOWS, "trigger_changed"),
    CareRule(HttpMethod.Delete, "^/v1/triggers/$ID$", CarePermission.EDIT_FLOWS, "trigger_removed"),
    CareRule(HttpMethod.Post, "^/v1/marketplace/$ID/import$", CarePermission.EDIT_FLOWS, "flow_added"),
    CareRule(HttpMethod.Post, "^/v1/run-requests$", CarePermission.RUN_FLOWS, "flow_run"),
    CareRule(HttpMethod.Post, "^/v1/run-requests/$ID/cancel$", CarePermission.RUN_FLOWS, "run_stopped"),
    CareRule(HttpMethod.Get, "^/v1/runs(/stats|/$ID)?$", CarePermission.VIEW_HISTORY),
)

private val flowInPath = Regex("^/v1/flows/($ID)")

/**
 * Remote caregiver mode: with `X-Care-Link: <link id>`, a signed-in caregiver works on the account of
 * the person they help, within the permissions that person granted. Actions are recorded on the link.
 */
fun Route.careContext(care: CareService, flows: FlowService) {
    // Runs in the Call phase of every route, after that route's authentication.
    (this as RoutingNode).intercept(ApplicationCallPipeline.Call) {
        val call = context
        val header = call.request.headers[CARE_HEADER]?.trim()?.takeIf { it.isNotEmpty() } ?: return@intercept
        val path = call.request.path()
        if (path.startsWith("/v1/care") || path.startsWith("/v1/auth")) return@intercept
        val method = call.request.httpMethod
        val rule = rules.firstOrNull { it.method == method && it.regex.matches(path) }
        if (rule == null) {
            if (method == HttpMethod.Get) return@intercept
            throw DomainException.Forbidden("This isn't available while helping someone")
        }
        val caller = call.principal<JWTPrincipal>()?.payload?.subject?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        if (caller == null) {
            if (call.principal<ApiKeyPrincipal>() != null) throw DomainException.Forbidden("API keys can't act for someone else")
            return@intercept
        }
        if (!call.request.headers[ORG_HEADER].isNullOrBlank()) throw DomainException.Validation("Choose either an organization or a person you help, not both")
        val linkId = runCatching { UUID.fromString(header) }.getOrElse { throw DomainException.Validation("$CARE_HEADER must be a care link id") }
        val acting = care.actingAs(caller, linkId, rule.needs)
        call.attributes.put(ActingAsKey, acting)
        proceed()
        val action = rule.action ?: return@intercept
        if (call.response.status()?.isSuccess() != true) return@intercept
        val details = buildMap {
            flowInPath.find(path)?.groupValues?.get(1)?.let { id ->
                put("flowId", id)
                runCatching { flows.get(acting.receiverId, UUID.fromString(id)) }.getOrNull()?.let { put("flowName", it.flow.name) }
            }
        }
        runCatching { care.record(acting, action, details) }
    }
}
