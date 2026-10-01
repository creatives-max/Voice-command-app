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
    val appPackage: String = "",
    val tags: List<String> = emptyList(),
    val ownerName: String? = null,
    val mine: Boolean = false,
    val installCount: Int = 0,
    val ratingAverage: Double? = null,
    val ratingCount: Int = 0,
    val hidden: Boolean = false,
)

@Serializable data class RatingDto(val stars: Int, val review: String? = null, val userName: String? = null, val updatedAt: String = "")
@Serializable data class ListingVersionDto(val version: Int, val changelog: String? = null, val stepCount: Int = 0, val createdAt: String = "")

/** A marketplace listing with its steps, versions and reviews, as seen by the signed-in user. */
@Serializable data class ListingDetailDto(
    val listing: ListingDto,
    val steps: List<com.voicecontrol.core.model.FlowStep> = emptyList(),
    val versions: List<ListingVersionDto> = emptyList(),
    val myRating: RatingDto? = null,
    val reviews: List<RatingDto> = emptyList(),
    val importedFlowId: String? = null,
    val importedVersion: Int? = null,
    val updateAvailable: Boolean = false,
    val myReport: String? = null,
)

@Serializable data class ImportListingRequestDto(val version: Int? = null)
@Serializable data class RateListingRequestDto(val stars: Int, val review: String? = null)
@Serializable data class ReportListingRequestDto(val reason: String, val note: String? = null)
@Serializable data class ReportListingResponseDto(val hidden: Boolean)

@Serializable data class TemplateDto(
    val listing: ListingDto,
    val steps: List<com.voicecontrol.core.model.FlowStep>,
    val keywords: Map<String, List<String>> = emptyMap(),
)
