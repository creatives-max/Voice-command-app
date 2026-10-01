package com.voicecontrol.core.engine.port

import com.voicecontrol.core.model.FlowDefinition
import com.voicecontrol.core.model.ScreenSnapshot
import com.voicecontrol.core.model.SessionSummary
import com.voicecontrol.core.model.UserProfile

/** Finds the saved (possibly dashboard-edited) flow that best matches a screen. */
fun interface FlowSource {
    suspend fun flowFor(snapshot: ScreenSnapshot): FlowDefinition?
}

/** Supplies the user's saved profile for pre-filling common fields. */
fun interface ProfileSource {
    suspend fun profile(): UserProfile?
}

/** Persists finished sessions (history) and turns them into flows. */
fun interface SessionRecorder {
    suspend fun record(summary: SessionSummary)
}
