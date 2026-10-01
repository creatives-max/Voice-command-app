package com.voicecontrol.core.data.shortcuts

import com.voicecontrol.core.data.automation.RemoteRunRepository
import com.voicecontrol.core.data.flows.FlowRepository
import com.voicecontrol.core.data.settings.SettingsRepository
import com.voicecontrol.core.engine.ShortcutMatcher
import com.voicecontrol.core.engine.VoiceShortcut
import com.voicecontrol.core.engine.port.ShortcutSource
import com.voicecontrol.core.model.FlowDefinition
import com.voicecontrol.core.network.NetworkJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import javax.inject.Inject
import javax.inject.Singleton

/** A voice shortcut made on this phone (works without an account). */
@Serializable
data class LocalShortcut(val phrase: String, val flowId: String, val flowName: String? = null)

/**
 * Voice macros: phrases that run a flow. Shortcuts made on this phone are kept on the phone; those set
 * on the dashboard (VOICE triggers) come with the account and are cached for offline use.
 */
@Singleton
class VoiceShortcutRepository @Inject constructor(
    private val settings: SettingsRepository,
    private val flows: FlowRepository,
    private val remote: RemoteRunRepository,
) : ShortcutSource {

    private val serializer = ListSerializer(LocalShortcut.serializer())

    val local: Flow<List<LocalShortcut>> = settings.localShortcutsJson.map { json ->
        json?.let { runCatching { NetworkJson.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()
    }

    /** Every shortcut: the account's first, then this phone's. */
    val all: Flow<List<VoiceShortcut>> = combine(remote.voiceTriggers, local) { server, phone ->
        server.map { VoiceShortcut(it.phrase, it.flowId, it.flowName, it.id) } + phone.map { VoiceShortcut(it.phrase, it.flowId, it.flowName) }
    }

    private val _started = MutableSharedFlow<VoiceShortcut>(extraBufferCapacity = 4)
    /** Shortcuts whose flow just started from speech. */
    val started: SharedFlow<VoiceShortcut> = _started.asSharedFlow()

    override suspend fun shortcuts(): List<VoiceShortcut> {
        remote.loadCachedTriggers()
        return all.first()
    }

    override suspend fun flow(flowId: String): FlowDefinition? =
        flows.get(flowId) ?: if (!flowId.startsWith(FlowDefinition.LOCAL_PREFIX) && remote.online()) remote.loadFlow(flowId) else null

    override suspend fun started(shortcut: VoiceShortcut) {
        _started.tryEmit(shortcut)
    }

    /** Adds a phrase for [flow]; fails with a message the user can read. */
    suspend fun add(phrase: String, flow: FlowDefinition): Result<Unit> {
        val clean = phrase.trim().replace(Regex("\\s+"), " ")
        ShortcutMatcher.validate(clean)?.let { return Result.failure(IllegalArgumentException(it)) }
        val mine = local.first()
        if (mine.size >= MAX_LOCAL) return Result.failure(IllegalArgumentException("You can have at most $MAX_LOCAL voice shortcuts on this phone"))
        ShortcutMatcher.conflict(clean, shortcuts())?.let { used ->
            val owner = if (used.flowId == flow.id) "this flow" else "“${used.flowName ?: "another flow"}”"
            return Result.failure(IllegalArgumentException("\"$clean\" already runs $owner"))
        }
        save(mine + LocalShortcut(clean, flow.id, flow.name))
        return Result.success(Unit)
    }

    suspend fun remove(phrase: String, flowId: String) {
        save(local.first().filterNot { it.phrase == phrase && it.flowId == flowId })
    }

    /** A phone-only flow was uploaded and got the account's id. */
    suspend fun renameFlow(oldId: String, newId: String) {
        val mine = local.first()
        if (mine.any { it.flowId == oldId }) save(mine.map { if (it.flowId == oldId) it.copy(flowId = newId) else it })
    }

    /** Drops the phone's shortcuts of a deleted flow. */
    suspend fun removeFlow(flowId: String) {
        val mine = local.first()
        if (mine.any { it.flowId == flowId }) save(mine.filterNot { it.flowId == flowId })
    }

    private suspend fun save(list: List<LocalShortcut>) {
        settings.saveLocalShortcutsJson(NetworkJson.encodeToString(serializer, list))
    }

    companion object {
        const val MAX_LOCAL = 50
    }
}
