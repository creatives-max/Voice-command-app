package com.voicecontrol.core.network.dto

import kotlinx.serialization.Serializable

@Serializable data class CareInviteRequestDto(val permissions: List<String>)
@Serializable data class CarePermissionsRequestDto(val permissions: List<String>)

/** A caregiver link as the backend returns it; [role] "receiver" means this user is the one helped. */
@Serializable data class CareLinkDto(
    val id: String,
    val role: String,
    val status: String,
    val permissions: List<String> = emptyList(),
    val otherEmail: String? = null,
    val otherName: String? = null,
    val createdAt: String,
    val acceptedAt: String? = null,
    val expiresAt: String? = null,
)

@Serializable data class CareInviteDto(val link: CareLinkDto, val code: String, val expiresAt: String)

@Serializable data class CareEventDto(
    val id: Long,
    val action: String,
    val actorEmail: String? = null,
    val details: Map<String, String> = emptyMap(),
    val at: String,
)
