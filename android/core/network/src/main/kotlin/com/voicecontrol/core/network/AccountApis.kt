package com.voicecontrol.core.network

import com.voicecontrol.core.model.FlowDefinition
import com.voicecontrol.core.model.UserProfile
import com.voicecontrol.core.network.dto.AuthResponseDto
import com.voicecontrol.core.network.dto.CreateFlowRequestDto
import com.voicecontrol.core.network.dto.FlowSummaryDto
import com.voicecontrol.core.network.dto.LoginRequestDto
import com.voicecontrol.core.network.dto.LogoutRequestDto
import com.voicecontrol.core.network.dto.MatchRequestDto
import com.voicecontrol.core.network.dto.MatchResponseDto
import com.voicecontrol.core.network.dto.PageDto
import com.voicecontrol.core.network.dto.RegisterRequestDto
import com.voicecontrol.core.network.dto.UserDto
import io.ktor.client.plugins.timeout
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthApi @Inject constructor(private val api: ApiClient) {
    suspend fun register(email: String, password: String, name: String?): AuthResponseDto =
        api.post<RegisterRequestDto, AuthResponseDto>("/v1/auth/register", RegisterRequestDto(email, password, name), authenticated = false)

    suspend fun login(email: String, password: String): AuthResponseDto =
        api.post<LoginRequestDto, AuthResponseDto>("/v1/auth/login", LoginRequestDto(email, password), authenticated = false)

    suspend fun logout(refreshToken: String?) {
        api.send(HttpMethod.Post, "/v1/auth/logout") { setBody(LogoutRequestDto(refreshToken)) }
    }

    suspend fun me(): UserDto = api.get("/v1/me")

    /** Everything the server stores about the user, as JSON text (GDPR access). */
    suspend fun export(): String = api.send(HttpMethod.Get, "/v1/me/export").bodyAsText()

    /** Deletes the account and its data on the server (GDPR erasure). */
    suspend fun deleteAccount(password: String) {
        api.send(HttpMethod.Delete, "/v1/me") { setBody(com.voicecontrol.core.network.dto.DeleteAccountRequestDto(password)) }
    }
}

/** Self-hosted crash reports (sent only when the user turned crash reports on). */
@Singleton
class CrashApi @Inject constructor(private val api: ApiClient) {
    suspend fun upload(reports: List<com.voicecontrol.core.model.diagnostics.CrashRecord>): Int =
        api.post<com.voicecontrol.core.network.dto.CrashUploadDto, com.voicecontrol.core.network.dto.CrashUploadResponseDto>(
            "/v1/crashes",
            com.voicecontrol.core.network.dto.CrashUploadDto(reports),
        ).accepted
}

@Singleton
class ProfileApi @Inject constructor(private val api: ApiClient) {
    suspend fun get(): UserProfile = api.get("/v1/profile")
    suspend fun put(profile: UserProfile): UserProfile = api.put<UserProfile, UserProfile>("/v1/profile", profile)
}

@Singleton
class FlowApi @Inject constructor(private val api: ApiClient) {
    suspend fun create(flow: FlowDefinition): FlowDefinition = api.post<CreateFlowRequestDto, FlowDefinition>(
        "/v1/flows",
        CreateFlowRequestDto(
            flow.appPackage, flow.name, flow.screenSignature, flow.orderedSteps,
            source = if (flow.id.startsWith(FlowDefinition.TAUGHT_PREFIX)) "RECORDED" else null,
        ),
    )

    suspend fun get(id: String): FlowDefinition = api.get("/v1/flows/$id")

    /** Closest saved flow for a screen (exact signature or pgvector similarity), or null. */
    suspend fun match(appPackage: String, signature: String): MatchResponseDto =
        api.post<MatchRequestDto, MatchResponseDto>("/v1/flows/match", MatchRequestDto(appPackage, signature))

    suspend fun delete(id: String) = api.delete("/v1/flows/$id")

    /** Saves an edit made on the phone as a new version (fails with 409 if the flow changed meanwhile). */
    suspend fun update(flow: FlowDefinition, changeNote: String?): FlowDefinition =
        api.put<com.voicecontrol.core.network.dto.UpdateFlowRequestDto, FlowDefinition>(
            "/v1/flows/${flow.id}",
            com.voicecontrol.core.network.dto.UpdateFlowRequestDto(flow.version, flow.name, flow.orderedSteps, changeNote),
        )

    suspend fun list(appPackage: String? = null, limit: Int = 200): List<FlowSummaryDto> =
        api.get<PageDto<FlowSummaryDto>>("/v1/flows") {
            url.parameters.append("limit", limit.toString())
            if (appPackage != null) url.parameters.append("appPackage", appPackage)
        }.items
}

@Singleton
class HistoryApi @Inject constructor(private val api: ApiClient) {
    suspend fun upload(runs: List<com.voicecontrol.core.model.SessionSummary>): Int =
        api.post<com.voicecontrol.core.network.dto.UploadRunsDto, com.voicecontrol.core.network.dto.UploadRunsResponseDto>(
            "/v1/runs",
            com.voicecontrol.core.network.dto.UploadRunsDto(runs),
        ).inserted

    suspend fun clear() = api.delete("/v1/runs")
}

/** Remote runs: device registration, the command long-poll, app-open triggers and run reports. */
@Singleton
class AutomationApi @Inject constructor(private val api: ApiClient) {
    suspend fun register(device: com.voicecontrol.core.network.dto.RegisterDeviceDto): com.voicecontrol.core.network.dto.DeviceDto =
        api.post<com.voicecontrol.core.network.dto.RegisterDeviceDto, com.voicecontrol.core.network.dto.DeviceDto>("/v1/devices", device)

    /** Waits up to [waitSeconds] for flows to run or runs to stop on this phone. */
    suspend fun commands(deviceId: String, waitSeconds: Int): com.voicecontrol.core.network.dto.DeviceCommandsDto =
        api.get("/v1/devices/$deviceId/commands") {
            url.parameters.append("wait", waitSeconds.toString())
            timeout {
                requestTimeoutMillis = (waitSeconds + 15) * 1_000L
                socketTimeoutMillis = (waitSeconds + 15) * 1_000L
            }
        }

    suspend fun triggers(deviceId: String): List<com.voicecontrol.core.network.dto.TriggerDto> = api.get("/v1/devices/$deviceId/triggers")

    suspend fun appOpenRun(deviceId: String, flowId: String, triggerId: String?): com.voicecontrol.core.network.dto.RunRequestDto =
        api.post<com.voicecontrol.core.network.dto.AppOpenRunDto, com.voicecontrol.core.network.dto.RunRequestDto>(
            "/v1/devices/$deviceId/app-open-runs",
            com.voicecontrol.core.network.dto.AppOpenRunDto(flowId, triggerId),
        )

    suspend fun report(requestId: String, report: com.voicecontrol.core.network.dto.RunReportDto): com.voicecontrol.core.network.dto.RunRequestDto =
        api.post<com.voicecontrol.core.network.dto.RunReportDto, com.voicecontrol.core.network.dto.RunRequestDto>("/v1/run-requests/$requestId/report", report)
}

@Singleton
class MarketplaceApi @Inject constructor(private val api: ApiClient) {
    /** Built-in starter templates with their matching keywords. */
    suspend fun templates(): List<com.voicecontrol.core.network.dto.TemplateDto> = api.get("/v1/marketplace/templates")
}
