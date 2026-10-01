package com.voicecontrol.api.routes

import com.voicecontrol.application.auth.AuthResult
import com.voicecontrol.application.flow.FlowSimulator
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

@Serializable data class UserDto(
    val id: String,
    val email: String,
    val name: String? = null,
    val createdAt: String,
    val passwordChangedAt: String? = null,
) {
    companion object {
        fun from(u: User) = UserDto(u.id.toString(), u.email, u.name, u.createdAt.toString(), u.passwordChangedAt?.toString())
    }
}
@Serializable data class ChangePasswordRequest(val currentPassword: String, val newPassword: String)
@Serializable data class SessionDto(val id: String, val client: String? = null, val createdAt: String, val lastUsedAt: String, val current: Boolean) {
    companion object {
        fun from(v: com.voicecontrol.application.auth.SessionView) =
            SessionDto(v.info.id, v.info.client, v.info.createdAt.toString(), v.info.lastUsedAt.toString(), v.current)
    }
}

@Serializable data class AuthResponse(val accessToken: String, val refreshToken: String, val expiresInSeconds: Long, val user: UserDto) {
    companion object {
        fun from(r: AuthResult) = AuthResponse(r.tokens.accessToken, r.tokens.refreshToken, r.tokens.expiresInSeconds, UserDto.from(r.user))
    }
}

@Serializable data class CreateFlowRequest(
    val appPackage: String,
    val name: String,
    val screenSignature: String,
    val steps: List<FlowStep>,
    /** "RECORDED" when the flow was taught on the phone by demonstration. */
    val source: String? = null,
)
@Serializable data class UpdateFlowRequest(val expectedVersion: Int, val name: String? = null, val steps: List<FlowStep>, val changeNote: String? = null)
@Serializable data class RollbackRequest(val version: Int)

@Serializable data class FlowSummaryDto(
    val id: String,
    val appPackage: String,
    val name: String,
    val currentVersion: Int,
    val createdAt: String,
    val updatedAt: String,
    /** Owning organization; null for a personal flow. */
    val orgId: String? = null,
) {
    companion object {
        fun from(f: Flow) =
            FlowSummaryDto(f.id.toString(), f.appPackage, f.name, f.currentVersion, f.createdAt.toString(), f.updatedAt.toString(), f.orgId?.toString())
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
    /** Marketplace listing this flow was imported from, and which version. */
    val sourcePublishedId: String? = null,
    val sourceVersion: Int? = null,
    /** Owning organization; null for a personal flow. */
    val orgId: String? = null,
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
            sourcePublishedId = fv.flow.sourcePublishedId?.toString(),
            sourceVersion = fv.flow.sourceVersion,
            orgId = fv.flow.orgId?.toString(),
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

/**
 * Dry run of a flow with scripted [answers]. [steps] simulates unsaved edits instead of the saved
 * version; [useProfile] lets profile values answer questions (signed-in people only).
 */
@Serializable data class DryRunRequest(
    val answers: List<String> = emptyList(),
    val steps: List<FlowStep>? = null,
    val useProfile: Boolean = true,
    val today: String? = null,
)
@Serializable data class DryRunEntryDto(val kind: String, val text: String, val stepId: String? = null)
@Serializable data class DryRunPendingDto(val stepId: String, val question: String, val expects: String)
@Serializable data class DryRunResponse(
    val flowVersion: Int,
    val transcript: List<DryRunEntryDto>,
    val vars: Map<String, String>,
    val values: Map<String, String>,
    val pending: DryRunPendingDto? = null,
    val finished: Boolean,
) {
    companion object {
        fun from(version: Int, r: FlowSimulator.Result) = DryRunResponse(
            flowVersion = version,
            transcript = r.transcript.map { DryRunEntryDto(it.kind.name.lowercase(), it.text, it.stepId) },
            vars = r.vars,
            values = r.values,
            pending = r.pending?.let { DryRunPendingDto(it.stepId, it.question, it.expects.name.lowercase()) },
            finished = r.finished,
        )
    }
}
