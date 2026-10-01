package com.voicecontrol.core.data.flows

import com.voicecontrol.core.data.StorageJson
import com.voicecontrol.core.data.db.FlowDao
import com.voicecontrol.core.data.db.FlowEntity
import com.voicecontrol.core.engine.FlowGenerator
import com.voicecontrol.core.model.FlowDefinition
import com.voicecontrol.core.model.FlowStep
import com.voicecontrol.core.model.ScreenSnapshot
import com.voicecontrol.core.model.SessionSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Local store of flows: created from recorded sessions and cached from the server. */
@Singleton
class FlowRepository @Inject constructor(
    private val dao: FlowDao,
) {
    private val stepsSerializer = ListSerializer(FlowStep.serializer())

    fun observeFlows(): Flow<List<FlowDefinition>> = dao.observeAll().map { rows -> rows.map(::toModel) }

    suspend fun get(id: String): FlowDefinition? = dao.byId(id)?.let(::toModel)

    /** Best local match for the screen (exact signature, then structural similarity). */
    suspend fun localMatch(snapshot: ScreenSnapshot): FlowDefinition? =
        FlowMatcher.best(snapshot, dao.byPackage(snapshot.packageName).map(::toModel))

    suspend fun save(flow: FlowDefinition, synced: Boolean) = dao.upsert(toEntity(flow, synced))

    suspend fun delete(id: String) = dao.delete(id)

    suspend fun unsynced(): List<FlowDefinition> = dao.unsynced().map(::toModel)

    /** Server-synced flows by id (local drafts excluded). */
    suspend fun syncedSnapshot(): Map<String, FlowDefinition> =
        dao.synced().map(::toModel).associateBy { it.id }

    /** Replaces a local draft with its server copy once uploaded. */
    suspend fun replace(localId: String, serverFlow: FlowDefinition) {
        if (localId != serverFlow.id) dao.delete(localId)
        dao.upsert(toEntity(serverFlow, synced = true))
    }

    /**
     * Creates local flows for screens handled without a saved flow, so the next run on the same screen
     * reuses the same order and questions. Screens that already have a flow keep it (and its edits).
     */
    suspend fun createFromSession(summary: SessionSummary, nowMillis: Long): List<FlowDefinition> {
        val created = mutableListOf<FlowDefinition>()
        for (screen in summary.screens) {
            if (screen.flowId != null || screen.steps.isEmpty()) continue
            val existing = dao.byPackage(screen.appPackage).any { it.screenSignature == screen.screenSignature }
            if (existing) continue
            val flow = FlowGenerator.fromScreen(screen, FlowDefinition.LOCAL_PREFIX + UUID.randomUUID(), nowMillis)
            dao.upsert(toEntity(flow, synced = false))
            created += flow
        }
        return created
    }

    private fun toModel(e: FlowEntity) = FlowDefinition(
        id = e.id,
        version = e.version,
        appPackage = e.appPackage,
        name = e.name,
        screenSignature = e.screenSignature,
        steps = runCatching { StorageJson.decodeFromString(stepsSerializer, e.stepsJson) }.getOrDefault(emptyList()),
        updatedAtMillis = e.updatedAtMillis,
    )

    private fun toEntity(f: FlowDefinition, synced: Boolean) = FlowEntity(
        id = f.id,
        appPackage = f.appPackage,
        name = f.name,
        screenSignature = f.screenSignature,
        version = f.version,
        stepsJson = StorageJson.encodeToString(stepsSerializer, f.steps),
        updatedAtMillis = f.updatedAtMillis,
        synced = synced,
    )
}
