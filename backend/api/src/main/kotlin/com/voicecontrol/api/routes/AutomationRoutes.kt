package com.voicecontrol.api.routes

import com.voicecontrol.api.plugins.API_KEY_AUTH
import com.voicecontrol.api.plugins.JWT_AUTH
import com.voicecontrol.api.plugins.keyContext
import com.voicecontrol.api.plugins.requireScope
import com.voicecontrol.application.flow.FlowService
import com.voicecontrol.domain.org.ApiScope
import com.voicecontrol.api.plugins.userId
import com.voicecontrol.application.automation.CronSchedule
import com.voicecontrol.application.automation.DeviceService
import com.voicecontrol.application.automation.RunRequestService
import com.voicecontrol.application.automation.TriggerInput
import com.voicecontrol.application.automation.TriggerService
import com.voicecontrol.domain.automation.Device
import com.voicecontrol.domain.automation.FlowTrigger
import com.voicecontrol.domain.automation.RunEvent
import com.voicecontrol.domain.automation.RunRequest
import com.voicecontrol.domain.automation.RunRequestStatus
import com.voicecontrol.domain.automation.RunSource
import com.voicecontrol.domain.automation.TriggerType
import com.voicecontrol.domain.common.DomainException
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
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

@Serializable data class RegisterDeviceRequest(
    val id: String,
    val name: String,
    val platform: String = "android",
    val appVersion: String? = null,
    val remoteRuns: Boolean? = null,
)
@Serializable data class UpdateDeviceRequest(val name: String? = null, val remoteRuns: Boolean? = null)
@Serializable data class DeviceDto(
    val id: String,
    val name: String,
    val platform: String,
    val appVersion: String? = null,
    val remoteRuns: Boolean,
    val lastSeenAt: String,
    val online: Boolean,
    val createdAt: String,
) {
    companion object {
        fun from(d: Device, now: Instant = Instant.now()) = DeviceDto(
            d.id.toString(), d.name, d.platform, d.appVersion, d.remoteRuns, d.lastSeenAt.toString(),
            d.lastSeenAt.isAfter(now.minus(DeviceService.ONLINE_WINDOW)), d.createdAt.toString(),
        )
    }
}

@Serializable data class TriggerRequest(
    val type: TriggerType,
    val enabled: Boolean = true,
    val cron: String? = null,
    val timezone: String? = null,
    val deviceId: String? = null,
    /** VOICE: what the user says to run the flow. */
    val phrase: String? = null,
) {
    fun toInput() = TriggerInput(type, enabled, cron, timezone, deviceId?.takeIf { it.isNotBlank() }?.let { parseUuid(it, "deviceId") }, phrase)
}

@Serializable data class TriggerDto(
    val id: String,
    val flowId: String,
    val type: TriggerType,
    val enabled: Boolean,
    val cron: String? = null,
    val timezone: String? = null,
    val deviceId: String? = null,
    val nextRunAt: String? = null,
    val lastRunAt: String? = null,
    /** The next few run times (schedules only), for the dashboard preview. */
    val upcoming: List<String> = emptyList(),
    /** App of the flow, so the phone can match app-open triggers without loading the flow. */
    val appPackage: String? = null,
    /** VOICE: what the user says to run the flow. */
    val phrase: String? = null,
    /** Name of the flow (device list only), for the phone's shortcut list. */
    val flowName: String? = null,
) {
    companion object {
        fun from(t: FlowTrigger, appPackage: String? = null, flowName: String? = null): TriggerDto {
            val upcoming = if (t.type == TriggerType.SCHEDULE && t.enabled && t.cron != null && t.timezone != null) {
                runCatching { CronSchedule.parse(t.cron!!).upcoming(Instant.now(), ZoneId.of(t.timezone), 3).map(Instant::toString) }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
            return TriggerDto(
                t.id.toString(), t.flowId.toString(), t.type, t.enabled, t.cron, t.timezone, t.deviceId?.toString(),
                t.nextRunAt?.toString(), t.lastRunAt?.toString(), upcoming, appPackage, t.phrase, flowName,
            )
        }
    }
}

@Serializable data class RunNowRequest(val flowId: String, val deviceId: String? = null)
/** A run the phone started by itself: APP_OPEN (its app opened) or VOICE (a voice shortcut was said). */
@Serializable data class AppOpenRunRequest(val flowId: String, val triggerId: String? = null, val source: RunSource = RunSource.APP_OPEN)
@Serializable data class RunRequestDto(
    val id: String,
    val flowId: String? = null,
    val flowName: String,
    val appPackage: String,
    val deviceId: String? = null,
    val triggerId: String? = null,
    val source: RunSource,
    val status: RunRequestStatus,
    val createdAt: String,
    val updatedAt: String,
) {
    companion object {
        fun from(r: RunRequest) = RunRequestDto(
            r.id.toString(), r.flowId?.toString(), r.flowName, r.appPackage, r.deviceId?.toString(), r.triggerId?.toString(),
            r.source, r.status, r.createdAt.toString(), r.updatedAt.toString(),
        )
    }
}
@Serializable data class DeviceCommandsDto(val run: List<RunRequestDto>, val cancel: List<String>)
@Serializable data class RunEventInDto(val kind: String, val message: String)
@Serializable data class RunReportRequest(val deviceId: String, val status: RunRequestStatus? = null, val events: List<RunEventInDto> = emptyList())
@Serializable data class RunEventDto(val id: Long, val at: String, val kind: String, val message: String) {
    companion object {
        fun from(e: RunEvent) = RunEventDto(e.id, e.at.toString(), e.kind, e.message)
    }
}
@Serializable data class RunEventsDto(val request: RunRequestDto, val events: List<RunEventDto>)

private fun parseUuid(value: String, name: String): UUID =
    runCatching { UUID.fromString(value) }.getOrElse { throw DomainException.Validation("$name must be a UUID") }

private fun ApplicationCall.idParam(name: String = "id"): UUID = parseUuid(parameters[name] ?: "", name)

private fun ApplicationCall.intQuery(name: String, default: Int): Int = request.queryParameters[name]?.toIntOrNull() ?: default

fun Route.automationRoutes(
    devices: DeviceService,
    triggers: TriggerService,
    runs: RunRequestService,
    flowInfo: suspend (UUID, UUID) -> Pair<String?, String?>,
    flows: FlowService,
) {
    // "Run now" is also available to organization API keys with runs:write: the run goes to the key
    // creator's phone, and only for the organization's flows.
    authenticate(JWT_AUTH, API_KEY_AUTH) {
        post("/v1/run-requests") {
            call.requireScope(ApiScope.RUNS_WRITE)
            val body = call.receive<RunNowRequest>()
            val flowId = parseUuid(body.flowId, "flowId")
            call.keyContext?.let { flows.get(call.userId, flowId, it) }
            val request = runs.runNow(call.userId, flowId, body.deviceId?.takeIf { it.isNotBlank() }?.let { parseUuid(it, "deviceId") })
            call.respond(HttpStatusCode.Created, RunRequestDto.from(request))
        }
    }
    authenticate(JWT_AUTH) {
        route("/v1/devices") {
            post {
                val body = call.receive<RegisterDeviceRequest>()
                val device = devices.register(call.userId, parseUuid(body.id, "id"), body.name, body.platform, body.appVersion, body.remoteRuns)
                call.respond(DeviceDto.from(device))
            }
            get { call.respond(devices.list(call.userId).map { DeviceDto.from(it) }) }
            route("/{id}") {
                patch {
                    val body = call.receive<UpdateDeviceRequest>()
                    call.respond(DeviceDto.from(devices.update(call.userId, call.idParam(), body.name, body.remoteRuns)))
                }
                delete {
                    devices.delete(call.userId, call.idParam())
                    call.respond(HttpStatusCode.NoContent)
                }
                // Long-poll used by the phone: run requests to start and runs to stop.
                get("/commands") {
                    val commands = runs.commands(call.userId, call.idParam(), call.intQuery("wait", 25))
                    call.respond(DeviceCommandsDto(commands.run.map(RunRequestDto::from), commands.cancel.map { it.id.toString() }))
                }
                // App-open triggers and voice shortcuts that apply to this phone.
                get("/triggers") {
                    val userId = call.userId
                    val deviceId = call.idParam()
                    val list = triggers.listForUser(userId)
                        .filter { it.type != TriggerType.SCHEDULE && it.enabled && (it.deviceId == null || it.deviceId == deviceId) }
                        .map { t -> flowInfo(userId, t.flowId).let { (app, name) -> TriggerDto.from(t, app, name) } }
                    call.respond(list)
                }
                post("/app-open-runs") {
                    val body = call.receive<AppOpenRunRequest>()
                    val request = runs.reportAppOpen(
                        call.userId, call.idParam(), parseUuid(body.flowId, "flowId"), body.triggerId?.let { parseUuid(it, "triggerId") }, body.source,
                    )
                    call.respond(HttpStatusCode.Created, RunRequestDto.from(request))
                }
            }
        }

        route("/v1/flows/{id}/triggers") {
            get { call.respond(triggers.listForFlow(call.userId, call.idParam()).map { TriggerDto.from(it) }) }
            post {
                val trigger = triggers.create(call.userId, call.idParam(), call.receive<TriggerRequest>().toInput())
                call.respond(HttpStatusCode.Created, TriggerDto.from(trigger))
            }
        }
        route("/v1/triggers/{id}") {
            put { call.respond(TriggerDto.from(triggers.update(call.userId, call.idParam(), call.receive<TriggerRequest>().toInput()))) }
            delete {
                triggers.delete(call.userId, call.idParam())
                call.respond(HttpStatusCode.NoContent)
            }
        }

        route("/v1/run-requests") {
            get {
                val flowId = call.request.queryParameters["flowId"]?.let { parseUuid(it, "flowId") }
                call.respond(runs.list(call.userId, flowId, call.intQuery("limit", 50)).map(RunRequestDto::from))
            }
            route("/{id}") {
                get { call.respond(RunRequestDto.from(runs.get(call.userId, call.idParam()))) }
                // Long-poll used by the dashboard's live log.
                get("/events") {
                    val (request, events) = runs.events(call.userId, call.idParam(), call.request.queryParameters["after"]?.toLongOrNull() ?: 0L, call.intQuery("wait", 0))
                    call.respond(RunEventsDto(RunRequestDto.from(request), events.map(RunEventDto::from)))
                }
                post("/cancel") { call.respond(RunRequestDto.from(runs.cancel(call.userId, call.idParam()))) }
                // Status + log lines from the phone.
                post("/report") {
                    val body = call.receive<RunReportRequest>()
                    val updated = runs.report(
                        call.userId, call.idParam(), parseUuid(body.deviceId, "deviceId"), body.status, body.events.map { it.kind to it.message },
                    )
                    call.respond(RunRequestDto.from(updated))
                }
            }
        }
    }
}
