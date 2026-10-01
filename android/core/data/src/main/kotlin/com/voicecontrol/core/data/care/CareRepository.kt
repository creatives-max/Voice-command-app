package com.voicecontrol.core.data.care

import com.voicecontrol.core.data.auth.AuthRepository
import com.voicecontrol.core.data.settings.SettingsRepository
import com.voicecontrol.core.network.CareApi
import com.voicecontrol.core.network.dto.CareEventDto
import com.voicecontrol.core.network.dto.CareInviteDto
import com.voicecontrol.core.network.dto.CareLinkDto
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** Why caregiving isn't available right now. */
enum class CareUnavailable { SIGNED_OUT, ON_DEVICE_ONLY }

/**
 * Caregivers for the person using this phone: invites with the permissions they agree to, what each
 * helper may do, what they did, and ending it. Needs an account (helpers work through the server).
 */
@Singleton
class CareRepository @Inject constructor(
    private val api: CareApi,
    private val auth: AuthRepository,
    private val settings: SettingsRepository,
) {
    suspend fun unavailable(): CareUnavailable? = when {
        settings.appSettings().localOnly -> CareUnavailable.ON_DEVICE_ONLY
        auth.user.first() == null -> CareUnavailable.SIGNED_OUT
        else -> null
    }

    suspend fun myEmail(): String? = auth.user.first()?.email

    suspend fun links(): Result<List<CareLinkDto>> = runCatching { api.links() }
    suspend fun invite(permissions: Set<String>): Result<CareInviteDto> = runCatching { api.invite(permissions.sorted()) }
    suspend fun setPermissions(id: String, permissions: Set<String>): Result<CareLinkDto> = runCatching { api.setPermissions(id, permissions.sorted()) }
    suspend fun end(id: String): Result<Unit> = runCatching { api.end(id) }
    suspend fun events(id: String): Result<List<CareEventDto>> = runCatching { api.events(id) }
}
