package com.voicecontrol.api.routes

import com.voicecontrol.application.auth.AuthResult
import com.voicecontrol.domain.flow.AppSummary
import com.voicecontrol.domain.flow.Flow
import com.voicecontrol.domain.flow.FlowStep
import com.voicecontrol.domain.flow.FlowVersion
import com.voicecontrol.domain.flow.FlowWithVersion
import com.voicecontrol.domain.flow.VersionSource
import com.voicecontrol.domain.user.User
import kotlinx.serialization.Serializable

@Serializable data class RegisterRequest(val email: String, val password: String, val name: String? = null)
@Serializable data class LoginRequest(val email: String, val password: String)
@Serializable data class RefreshRequest(val refreshToken: String)
@Serializable data class LogoutRequest(val refreshToken: String? = null, val everywhere: Boolean = false)

@Serializable data class UserDto(val id: String, val email: String, val name: String? = null, val createdAt: String) {
    companion object {
        fun from(u: User) = UserDto(u.id.toString(), u.email, u.name, u.createdAt.toString())
    }
}

@Serializable data class AuthResponse(val accessToken: String, val refreshToken: String, val expiresInSeconds: Long, val user: UserDto) {
    companion object {
        fun from(r: AuthResult) = AuthResponse(r.tokens.accessToken, r.tokens.refreshToken, r.tokens.expiresInSeconds, UserDto.from(r.user))
    }
}

@Serializable data class CreateFlowRequest(val appPackage: String, val name: String, val screenSignature: String, val steps: List<FlowStep>)
@Serializable data class UpdateFlowRequest(val expectedVersion: Int, val name: String? = null, val steps: List<FlowStep>, val changeNote: String? = null)
@Serializable data class RollbackRequest(val version: Int)

@Serializable data class FlowSummaryDto(
    val id: String,
    val appPackage: String,
    val name: String,
    val currentVersion: Int,
    val createdAt: String,
    val updatedAt: String,
) {
    companion object {
        fun from(f: Flow) = FlowSummaryDto(f.id.toString(), f.appPackage, f.name, f.currentVersion, f.createdAt.toString(), f.updatedAt.toString())
    }
}

/** Same shape as the phone's `FlowDefinition` so the app can use it directly. */
@Serializable data class FlowDto(
    val id: String,
    val version: Int,
    val appPackage: String,
    val name: String,
    val screenSignature: String,
    val steps: List<FlowStep>,
    val updatedAtMillis: Long,
    val createdAt: String,
    val updatedAt: String,
) {
    companion object {
        fun from(fv: FlowWithVersion) = FlowDto(
            id = fv.flow.id.toString(),
            version = fv.version.version,
            appPackage = fv.flow.appPackage,
            name = fv.flow.name,
            screenSignature = fv.flow.screenSignature,
            steps = fv.version.steps,
            updatedAtMillis = fv.flow.updatedAt.toEpochMilli(),
            createdAt = fv.flow.createdAt.toString(),
            updatedAt = fv.flow.updatedAt.toString(),
        )
    }
}

@Serializable data class FlowVersionDto(
    val version: Int,
    val steps: List<FlowStep>,
    val source: VersionSource,
    val changeNote: String? = null,
    val createdAt: String,
) {
    companion object {
        fun from(v: FlowVersion) = FlowVersionDto(v.version, v.steps, v.source, v.changeNote, v.createdAt.toString())
    }
}

@Serializable data class AppSummaryDto(val appPackage: String, val flowCount: Int, val lastUpdated: String) {
    companion object {
        fun from(a: AppSummary) = AppSummaryDto(a.appPackage, a.flowCount, a.lastUpdated.toString())
    }
}

@Serializable data class Page<T>(val items: List<T>, val limit: Int, val offset: Int)

@Serializable data class MatchRequest(val appPackage: String, val signature: String)
@Serializable data class MatchResponse(val flow: FlowDto? = null, val matchedBy: String? = null, val similarity: Double? = null)
