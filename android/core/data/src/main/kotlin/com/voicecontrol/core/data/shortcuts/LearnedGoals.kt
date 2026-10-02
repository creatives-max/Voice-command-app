package com.voicecontrol.core.data.shortcuts

import com.voicecontrol.core.data.flows.FlowRepository
import com.voicecontrol.core.data.sync.SyncScheduler
import com.voicecontrol.core.engine.port.GoalMemory
import com.voicecontrol.core.model.FlowDefinition
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Goals the helper reached ("mujhe bijli ka bill bharna hai") are kept as flows on this phone, with the
 * goal's words as their voice shortcut; they show up in My flows like taught flows.
 */
@Singleton
class LearnedGoals @Inject constructor(
    private val flows: FlowRepository,
    private val shortcuts: VoiceShortcutRepository,
    private val sync: SyncScheduler,
) : GoalMemory {
    override suspend fun learn(goal: String, flow: FlowDefinition): Boolean {
        flows.save(flow, synced = false)
        val added = shortcuts.add(goal, flow)
        if (added.isFailure) {
            flows.delete(flow.id)
            return false
        }
        sync.syncNow()
        return true
    }
}
