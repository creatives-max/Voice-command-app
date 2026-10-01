package com.voicecontrol.core.network

import com.voicecontrol.core.network.dto.CareEventDto
import com.voicecontrol.core.network.dto.CareInviteDto
import com.voicecontrol.core.network.dto.CareInviteRequestDto
import com.voicecontrol.core.network.dto.CareLinkDto
import com.voicecontrol.core.network.dto.CarePermissionsRequestDto
import javax.inject.Inject
import javax.inject.Singleton

/** Remote caregiver mode, from the side of the person being helped. */
@Singleton
class CareApi @Inject constructor(private val api: ApiClient) {
    suspend fun links(): List<CareLinkDto> = api.get("/v1/care/links")

    suspend fun invite(permissions: List<String>): CareInviteDto =
        api.post<CareInviteRequestDto, CareInviteDto>("/v1/care/invites", CareInviteRequestDto(permissions))

    suspend fun setPermissions(id: String, permissions: List<String>): CareLinkDto =
        api.patch<CarePermissionsRequestDto, CareLinkDto>("/v1/care/links/$id", CarePermissionsRequestDto(permissions))

    suspend fun end(id: String) = api.delete("/v1/care/links/$id")

    suspend fun events(id: String): List<CareEventDto> = api.get("/v1/care/links/$id/events?limit=30")
}
