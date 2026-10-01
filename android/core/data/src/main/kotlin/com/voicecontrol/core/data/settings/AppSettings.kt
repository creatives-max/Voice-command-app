package com.voicecontrol.core.data.settings

import com.voicecontrol.core.engine.port.SessionConfig
import com.voicecontrol.core.model.Language

/** All user-configurable settings, persisted in DataStore. */
data class AppSettings(
    val language: Language = Language.ENGLISH,
    val speechRate: Float = 1f,
    val transliterate: Boolean = true,
    val confirmValues: Boolean = true,
    val askBeforeSubmit: Boolean = true,
    val skipFilledFields: Boolean = false,
    /** No backend calls at all: no AI interpretation, no flow sync, no vision. */
    val localOnly: Boolean = false,
    val saveHistory: Boolean = true,
    val showOverlay: Boolean = true,
    /** Allow sending a screenshot to the backend when an app exposes no readable fields. */
    val visionFallback: Boolean = false,
    /** Start the voice session automatically when an app with a saved flow opens. */
    val autoStartWithFlow: Boolean = false,
    val backendUrl: String = "",
    /** Web dashboard where flows are edited; blank = the default for this build. */
    val dashboardUrl: String = "",
) {
    fun toSessionConfig() = SessionConfig(
        language = language,
        speechRate = speechRate,
        transliterate = transliterate,
        confirmValues = confirmValues,
        askBeforeSubmit = askBeforeSubmit,
        localOnly = localOnly,
        skipFilledFields = skipFilledFields,
        visionFallback = visionFallback,
    )
}
