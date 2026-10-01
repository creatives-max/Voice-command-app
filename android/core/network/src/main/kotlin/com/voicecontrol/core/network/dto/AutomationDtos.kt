package com.voicecontrol.core.network.dto

import kotlinx.serialization.Serializable

@Serializable data class RegisterDeviceDto(
    val id: String,
    val name: String,
    val platform: String = "android",
    val appVersion: String? = null,
    val remoteRuns: Boolean? = null,
)

@Serializable data class DeviceDto(val id: String, val name: String, val remoteRuns: Boolean, val lastSeenAt: String, val online: Boolean = false)

@Serializable data class RunRequestDto(
    val id: String,
    val flowId: String? = null,
    val flowName: String,
    val appPackage: String,
    val deviceId: String? = null,
    val triggerId: String? = null,
    val source: String,
    val status: String,
)

@Serializable data class DeviceCommandsDto(val run: List<RunRequestDto> = emptyList(), val cancel: List<String> = emptyList())

@Serializable data class TriggerDto(
    val id: String,
    val flowId: String,
    val type: String,
    val enabled: Boolean,
    val appPackage: String? = null,
    val deviceId: String? = null,
    /** VOICE triggers: what the user says to run the flow. */
    val phrase: String? = null,
    val flowName: String? = null,
)

/** A run the phone started by itself; [source] is APP_OPEN or VOICE. */
@Serializable data class AppOpenRunDto(val flowId: String, val triggerId: String? = null, val source: String = "APP_OPEN")

@Serializable data class RunEventInDto(val kind: String, val message: String)

@Serializable data class RunReportDto(val deviceId: String, val status: String? = null, val events: List<RunEventInDto> = emptyList())

@Serializable data class ListingDto(
    val id: String,
    val name: String,
    val description: String = "",
    val category: String,
    val isTemplate: Boolean = false,
    val latestVersion: Int,
)

@Serializable data class TemplateDto(
    val listing: ListingDto,
    val steps: List<com.voicecontrol.core.model.FlowStep>,
    val keywords: Map<String, List<String>> = emptyMap(),
)
