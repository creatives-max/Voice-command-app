package com.voicecontrol.core.data.teach

import com.voicecontrol.core.data.flows.FlowRepository
import com.voicecontrol.core.data.sync.SyncScheduler
import com.voicecontrol.core.engine.Recording
import com.voicecontrol.core.engine.RecordingToFlow
import com.voicecontrol.core.model.FlowDefinition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The last "teach by doing" recording, waiting for the user to review it. It lives only in memory, so
 * typed text is never written anywhere unless the user chooses to keep it as a default.
 */
@Singleton
class TaughtFlows @Inject constructor(
    private val flows: FlowRepository,
    private val sync: SyncScheduler,
) {
    private val _pending = MutableStateFlow<Recording?>(null)
    val pending: StateFlow<Recording?> = _pending.asStateFlow()

    fun setPending(recording: Recording) {
        _pending.value = recording.takeUnless { it.isEmpty }
    }

    /** Saves the pending recording as a flow on this phone (synced to the account later). */
    suspend fun save(name: String?, keepValues: Set<String>, nowMillis: Long = System.currentTimeMillis()): FlowDefinition {
        val recording = _pending.value ?: error("Nothing to save")
        val flow = RecordingToFlow.build(recording, FlowDefinition.TAUGHT_PREFIX + UUID.randomUUID(), nowMillis, keepValues, name)
        flows.save(flow, synced = false)
        _pending.value = null
        sync.syncNow()
        return flow
    }

    fun discard() {
        _pending.value = null
    }
}
