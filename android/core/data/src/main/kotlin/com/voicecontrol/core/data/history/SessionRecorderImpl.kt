package com.voicecontrol.core.data.history

import com.voicecontrol.core.data.flows.FlowRepository
import com.voicecontrol.core.data.settings.SettingsRepository
import com.voicecontrol.core.engine.port.SessionRecorder
import com.voicecontrol.core.model.SessionSummary
import javax.inject.Inject
import javax.inject.Singleton

/** Saves finished sessions to history (if enabled) and turns new screens into local flows. */
@Singleton
class SessionRecorderImpl @Inject constructor(
    private val history: HistoryRepository,
    private val flows: FlowRepository,
    private val settings: SettingsRepository,
) : SessionRecorder {

    override suspend fun record(summary: SessionSummary) {
        val prefs = settings.appSettings()
        if (prefs.saveHistory) history.add(summary)
        flows.createFromSession(summary, summary.endedAtMillis)
    }
}
