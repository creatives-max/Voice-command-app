package com.voicecontrol.core.engine.port

import com.voicecontrol.core.model.FlowDefinition
import com.voicecontrol.core.model.Language
import com.voicecontrol.core.model.ScreenElement
import com.voicecontrol.core.model.Screenshot
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

/**
 * Vision fallback for apps that expose no accessibility nodes (games, canvas/Flutter-without-semantics UIs):
 * detects fields and buttons on a screenshot. Returned element bounds are in *screen* pixels and ids
 * start with [VISION_ID_PREFIX].
 */
fun interface VisionDetector {
    suspend fun detect(screenshot: Screenshot, packageName: String, language: Language): List<ScreenElement>?

    companion object {
        const val VISION_ID_PREFIX = "vision:"
    }
}
