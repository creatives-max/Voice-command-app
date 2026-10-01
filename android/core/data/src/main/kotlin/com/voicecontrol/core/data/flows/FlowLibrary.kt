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
) {
    fun observe(): Flow<List<FlowDefinition>> = flows.observeFlows()

    suspend fun get(id: String): FlowDefinition? = flows.get(id)

    suspend fun delete(flow: FlowDefinition): Result<Unit> = runCatching {
        if (flow.isSynced) api.delete(flow.id)
        flows.delete(flow.id)
    }

    fun syncNow() = sync.syncNow(pullProfile = false)
}
