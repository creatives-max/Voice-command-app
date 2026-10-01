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
import io.ktor.client.request.setBody
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
        CreateFlowRequestDto(flow.appPackage, flow.name, flow.screenSignature, flow.orderedSteps),
    )

    suspend fun get(id: String): FlowDefinition = api.get("/v1/flows/$id")

    /** Closest saved flow for a screen (exact signature or pgvector similarity), or null. */
    suspend fun match(appPackage: String, signature: String): MatchResponseDto =
        api.post<MatchRequestDto, MatchResponseDto>("/v1/flows/match", MatchRequestDto(appPackage, signature))

    suspend fun delete(id: String) = api.delete("/v1/flows/$id")

    suspend fun list(appPackage: String? = null, limit: Int = 200): List<FlowSummaryDto> =
        api.get<PageDto<FlowSummaryDto>>("/v1/flows") {
            url.parameters.append("limit", limit.toString())
            if (appPackage != null) url.parameters.append("appPackage", appPackage)
        }.items
}
