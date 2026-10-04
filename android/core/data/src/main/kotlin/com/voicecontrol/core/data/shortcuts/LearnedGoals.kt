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
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
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

    /** Per-app notes from finished jobs, kept only on this phone (a few per app, newest last). */
    private val prefs by lazy { context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE) }

    override suspend fun notes(appPackage: String): List<String> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        prefs.getString(appPackage, null)?.split(SEPARATOR)?.filter { it.isNotBlank() }.orEmpty()
    }

    override suspend fun addNote(appPackage: String, note: String) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val clean = note.replace(SEPARATOR, " ").trim().take(MAX_NOTE_CHARS)
        if (clean.isEmpty()) return@withContext
        val kept = (notes(appPackage).filter { it != clean } + clean).takeLast(MAX_NOTES)
        prefs.edit().putString(appPackage, kept.joinToString(SEPARATOR)).apply()
    }

    private companion object {
        const val PREFS = "voicecontrol_app_notes"
        const val SEPARATOR = "\n"
        const val MAX_NOTES = 5
        const val MAX_NOTE_CHARS = 240
    }
}
