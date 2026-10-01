package com.voicecontrol.core.network.dto

import com.voicecontrol.core.model.FlowStep
import kotlinx.serialization.Serializable

@Serializable data class RegisterRequestDto(val email: String, val password: String, val name: String? = null)
@Serializable data class LoginRequestDto(val email: String, val password: String)
@Serializable data class LogoutRequestDto(val refreshToken: String? = null, val everywhere: Boolean = false)
@Serializable data class UserDto(val id: String, val email: String, val name: String? = null)
@Serializable data class AuthResponseDto(val accessToken: String, val refreshToken: String, val expiresInSeconds: Long, val user: UserDto)

@Serializable data class CreateFlowRequestDto(
    val appPackage: String,
    val name: String,
    val screenSignature: String,
    val steps: List<FlowStep>,
    /** "RECORDED" for flows taught by demonstration; null for flows recorded by a voice session. */
    val source: String? = null,
)
@Serializable data class FlowSummaryDto(val id: String, val appPackage: String, val name: String, val currentVersion: Int, val updatedAt: String)
@Serializable data class PageDto<T>(val items: List<T>, val limit: Int, val offset: Int)
@Serializable data class RefreshRequestDto(val refreshToken: String)
@Serializable data class MatchRequestDto(val appPackage: String, val signature: String)
@Serializable data class MatchResponseDto(
    val flow: com.voicecontrol.core.model.FlowDefinition? = null,
    val matchedBy: String? = null,
    val similarity: Double? = null,
)
@Serializable data class UploadRunsDto(val runs: List<com.voicecontrol.core.model.SessionSummary>)
@Serializable data class UploadRunsResponseDto(val inserted: Int)

@Serializable data class UpdateFlowRequestDto(val expectedVersion: Int, val name: String? = null, val steps: List<FlowStep>, val changeNote: String? = null)
@Serializable data class DeleteAccountRequestDto(val password: String)
@Serializable data class CrashUploadDto(val reports: List<com.voicecontrol.core.model.diagnostics.CrashRecord>)
@Serializable data class CrashUploadResponseDto(val accepted: Int)
