package com.voicecontrol.core.data.flows

import com.voicecontrol.core.data.auth.AuthRepository
import com.voicecontrol.core.data.settings.SettingsRepository
import com.voicecontrol.core.model.FlowDefinition
import com.voicecontrol.core.network.MarketplaceApi
import com.voicecontrol.core.network.dto.ListingDetailDto
import com.voicecontrol.core.network.dto.ListingDto
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** Why the marketplace can't be used right now. */
enum class MarketplaceUnavailable { SIGNED_OUT, ON_DEVICE_ONLY }

/** The flow marketplace on the phone: find flows others shared, install, rate and report them. */
@Singleton
class FlowMarketplace @Inject constructor(
    private val api: MarketplaceApi,
    private val flows: FlowRepository,
    private val auth: AuthRepository,
    private val settings: SettingsRepository,
) {
    suspend fun unavailable(): MarketplaceUnavailable? = when {
        settings.appSettings().localOnly -> MarketplaceUnavailable.ON_DEVICE_ONLY
        auth.user.first() == null -> MarketplaceUnavailable.SIGNED_OUT
        else -> null
    }

    suspend fun search(text: String?, category: String?, sort: String?, offset: Int = 0): Result<List<ListingDto>> =
        runCatching { api.search(text, category, sort, PAGE, offset).items }

    suspend fun categories(): Result<List<String>> = runCatching { api.categories() }
    suspend fun detail(id: String): Result<ListingDetailDto> = runCatching { api.listing(id) }

    /** Imports the listing into the account and keeps a copy on the phone right away. */
    suspend fun install(id: String): Result<FlowDefinition> = runCatching { api.import(id).also { flows.save(it, synced = true) } }

    suspend fun rate(id: String, stars: Int, review: String?): Result<ListingDto> = runCatching { api.rate(id, stars, review) }
    suspend fun report(id: String, reason: String, note: String?): Result<Boolean> = runCatching { api.report(id, reason, note).hidden }

    companion object {
        const val PAGE = 24
    }
}
