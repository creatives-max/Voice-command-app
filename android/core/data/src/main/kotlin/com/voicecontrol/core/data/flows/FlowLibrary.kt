package com.voicecontrol.core.data.flows

import com.voicecontrol.core.data.sync.SyncScheduler
import com.voicecontrol.core.model.FlowDefinition
import com.voicecontrol.core.network.FlowApi
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** Use cases for the Flows screen: browse, delete (locally and on the server), sync. */
@Singleton
class FlowLibrary @Inject constructor(
    private val flows: FlowRepository,
    private val api: FlowApi,
    private val sync: SyncScheduler,
    private val tokens: com.voicecontrol.core.data.auth.TokenStore,
    private val settings: com.voicecontrol.core.data.settings.SettingsRepository,
) {
    fun observe(): Flow<List<FlowDefinition>> = flows.observeFlows()

    suspend fun get(id: String): FlowDefinition? = flows.get(id)

    suspend fun delete(flow: FlowDefinition): Result<Unit> = runCatching {
        if (flow.isSynced) api.delete(flow.id)
        flows.delete(flow.id)
    }

    fun syncNow() = sync.syncNow(pullProfile = false)

    /**
     * Saves a flow edited on the phone. Account flows are saved as a new version on the server when
     * possible; on-device only (or signed out) the edit stays on this phone.
     */
    suspend fun saveEdited(flow: FlowDefinition, nowMillis: Long = System.currentTimeMillis()): Result<EditOutcome> = runCatching {
        val online = tokens.tokens() != null && !settings.appSettings().localOnly
        if (flow.isSynced && online) {
            try {
                flows.save(api.update(flow, "Edited on the phone"), synced = true)
                EditOutcome.SAVED_TO_ACCOUNT
            } catch (e: com.voicecontrol.core.network.ApiException) {
                throw IllegalStateException(
                    when {
                        e.status == 409 -> "This flow was changed on the dashboard. Sync, then edit again."
                        e.status == 403 -> "You can only view this organization flow"
                        e.isNetwork -> "Can't reach the server. Try again when you're online."
                        else -> e.message ?: "Save failed"
                    },
                )
            }
        } else {
            flows.save(flow.copy(updatedAtMillis = nowMillis), synced = flow.isSynced)
            EditOutcome.SAVED_ON_PHONE
        }
    }
}

enum class EditOutcome { SAVED_TO_ACCOUNT, SAVED_ON_PHONE }
