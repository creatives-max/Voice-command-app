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
    /** Let the dashboard ("Run now") and schedules start flows on this phone. */
    val remoteRuns: Boolean = true,
    /** Name shown for this phone in the dashboard; blank = the device model. */
    val deviceName: String = "",
    /** On screens without a saved flow, use a matching starter template (sign-up, login, address…). */
    val useTemplates: Boolean = true,
    /** Stop talking as soon as the user starts speaking. */
    val bargeIn: Boolean = false,
    /** Ask "Did you say …?" when speech recognition is unsure. */
    val confirmLowConfidence: Boolean = true,
    /** Listen for [wakeWord] while idle and start a session when it is heard. */
    val wakeWordEnabled: Boolean = false,
    /** Ask before pressing buttons that can't be undone (pay, delete, sign out…). */
    val confirmDestructive: Boolean = true,
    /** Recognize speech on the phone (downloaded language packs) instead of online; works without internet. */
    val offlineSpeech: Boolean = false,
    val wakeWord: String = "hey voice control",
    /** Ask for fingerprint, face or the screen lock to open VoiceControl. */
    val appLock: Boolean = false,
    /** How long VoiceControl may stay in the background before it locks again. */
    val lockTimeoutSeconds: Int = 60,
    /** Send crash reports (without personal data) to the VoiceControl server. */
    val crashReports: Boolean = false,
    /** The first-run tutorial was finished or skipped. */
    val onboardingDone: Boolean = false,
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
        bargeIn = bargeIn,
        confirmLowConfidence = confirmLowConfidence,
        confirmDestructive = confirmDestructive,
        // On-device only mode never uses online recognition either.
        preferOffline = offlineSpeech || localOnly,
    )
}
