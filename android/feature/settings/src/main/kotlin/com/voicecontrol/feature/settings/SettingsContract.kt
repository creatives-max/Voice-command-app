package com.voicecontrol.feature.settings

import com.voicecontrol.core.data.settings.AppSettings
import com.voicecontrol.core.model.Language

data class SettingsState(
    val loaded: Boolean = false,
    val settings: AppSettings = AppSettings(),
    val backendUrlDraft: String = "",
    val dashboardUrlDraft: String = "",
    val deviceNameDraft: String = "",
)

sealed interface SettingsIntent {
    data class SetLanguage(val language: Language) : SettingsIntent
    data class SetSpeechRate(val rate: Float) : SettingsIntent
    data class Toggle(val option: Option, val enabled: Boolean) : SettingsIntent
    data class EditBackendUrl(val value: String) : SettingsIntent
    data class EditDashboardUrl(val value: String) : SettingsIntent
    data object SaveUrls : SettingsIntent
    data class EditDeviceName(val value: String) : SettingsIntent
    data object SaveDeviceName : SettingsIntent
    data object TestVoice : SettingsIntent
}

enum class Option { TRANSLITERATE, CONFIRM_VALUES, ASK_BEFORE_SUBMIT, SKIP_FILLED, LOCAL_ONLY, SAVE_HISTORY, SHOW_OVERLAY, VISION_FALLBACK, AUTO_START, REMOTE_RUNS, USE_TEMPLATES }

sealed interface SettingsEffect {
    data class Message(val text: String) : SettingsEffect
}

fun AppSettings.with(option: Option, enabled: Boolean): AppSettings = when (option) {
    Option.TRANSLITERATE -> copy(transliterate = enabled)
    Option.CONFIRM_VALUES -> copy(confirmValues = enabled)
    Option.ASK_BEFORE_SUBMIT -> copy(askBeforeSubmit = enabled)
    Option.SKIP_FILLED -> copy(skipFilledFields = enabled)
    // Local-only and vision are mutually exclusive: vision needs the backend.
    Option.LOCAL_ONLY -> copy(localOnly = enabled, visionFallback = if (enabled) false else visionFallback)
    Option.SAVE_HISTORY -> copy(saveHistory = enabled)
    Option.SHOW_OVERLAY -> copy(showOverlay = enabled)
    Option.VISION_FALLBACK -> copy(visionFallback = enabled, localOnly = if (enabled) false else localOnly)
    Option.AUTO_START -> copy(autoStartWithFlow = enabled)
    Option.REMOTE_RUNS -> copy(remoteRuns = enabled)
    Option.USE_TEMPLATES -> copy(useTemplates = enabled)
}

fun AppSettings.isOn(option: Option): Boolean = when (option) {
    Option.TRANSLITERATE -> transliterate
    Option.CONFIRM_VALUES -> confirmValues
    Option.ASK_BEFORE_SUBMIT -> askBeforeSubmit
    Option.SKIP_FILLED -> skipFilledFields
    Option.LOCAL_ONLY -> localOnly
    Option.SAVE_HISTORY -> saveHistory
    Option.SHOW_OVERLAY -> showOverlay
    Option.VISION_FALLBACK -> visionFallback
    Option.AUTO_START -> autoStartWithFlow
    Option.REMOTE_RUNS -> remoteRuns
    Option.USE_TEMPLATES -> useTemplates
}

/** Accepts http(s) URLs only; blank means "use the build default". */
fun isValidServerUrl(url: String): Boolean {
    val v = url.trim()
    if (v.isEmpty()) return true
    return Regex("^https?://[A-Za-z0-9.\\-]+(:\\d{1,5})?(/.*)?$").matches(v)
}

fun sampleSentence(language: Language): String = when (language) {
    Language.ENGLISH -> "Hello! I will ask you what to fill. Say next, submit, back or scroll at any time."
    Language.HINDI -> "नमस्ते! मैं आपसे पूछूंगा कि क्या भरना है। आगे, जमा, वापस या नीचे कभी भी बोलिए।"
    Language.HINGLISH -> "Namaste! Main aapse poochunga kya bharna hai. Next, submit, back ya scroll kabhi bhi boliye."
}
