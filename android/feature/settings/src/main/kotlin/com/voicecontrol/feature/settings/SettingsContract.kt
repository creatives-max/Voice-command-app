package com.voicecontrol.feature.settings

import com.voicecontrol.core.data.settings.AppSettings
import com.voicecontrol.core.model.Language

data class SettingsState(
    val loaded: Boolean = false,
    val settings: AppSettings = AppSettings(),
    val backendUrlDraft: String = "",
    val dashboardUrlDraft: String = "",
    val deviceNameDraft: String = "",
    val wakeWordDraft: String = "",
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
    data class EditWakeWord(val value: String) : SettingsIntent
    data object SaveWakeWord : SettingsIntent
    data object TestVoice : SettingsIntent
}

enum class Option { TRANSLITERATE, CONFIRM_VALUES, ASK_BEFORE_SUBMIT, SKIP_FILLED, LOCAL_ONLY, SAVE_HISTORY, SHOW_OVERLAY, VISION_FALLBACK, AUTO_START, REMOTE_RUNS, USE_TEMPLATES, BARGE_IN, CONFIRM_LOW_CONFIDENCE, WAKE_WORD, CONFIRM_DESTRUCTIVE }

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
    Option.BARGE_IN -> copy(bargeIn = enabled)
    Option.CONFIRM_LOW_CONFIDENCE -> copy(confirmLowConfidence = enabled)
    Option.WAKE_WORD -> copy(wakeWordEnabled = enabled)
    Option.CONFIRM_DESTRUCTIVE -> copy(confirmDestructive = enabled)
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
    Option.BARGE_IN -> bargeIn
    Option.CONFIRM_LOW_CONFIDENCE -> confirmLowConfidence
    Option.WAKE_WORD -> wakeWordEnabled
    Option.CONFIRM_DESTRUCTIVE -> confirmDestructive
}

/** Accepts http(s) URLs only; blank means "use the build default". */
fun isValidServerUrl(url: String): Boolean {
    val v = url.trim()
    if (v.isEmpty()) return true
    return Regex("^https?://[A-Za-z0-9.\\-]+(:\\d{1,5})?(/.*)?$").matches(v)
}

fun sampleSentence(language: Language): String = when (language) {
    Language.MARATHI -> "नमस्कार! मी तुम्हाला काय भरायचे ते विचारेन. पुढे, जमा करा, मागे किंवा खाली कधीही म्हणा."
    Language.TAMIL -> "வணக்கம்! என்ன நிரப்ப வேண்டும் என்று கேட்பேன். அடுத்து, சமர்ப்பி, பின்னால் அல்லது கீழே எப்போது வேண்டுமானாலும் சொல்லுங்கள்."
    Language.TELUGU -> "నమస్కారం! ఏమి నింపాలో అడుగుతాను. తరువాత, సమర్పించు, వెనక్కి లేదా కిందకి ఎప్పుడైనా చెప్పండి."
    Language.BENGALI -> "নমস্কার! কী পূরণ করতে হবে আমি জিজ্ঞেস করব। পরের, জমা দাও, পিছনে বা নিচে যেকোনো সময় বলুন।"
    Language.GUJARATI -> "નમસ્તે! શું ભરવું છે તે હું પૂછીશ. આગળ, જમા કરો, પાછળ કે નીચે ક્યારેય પણ કહો."
    Language.ENGLISH -> "Hello! I will ask you what to fill. Say next, submit, back or scroll at any time."
    Language.HINDI -> "नमस्ते! मैं आपसे पूछूंगा कि क्या भरना है। आगे, जमा, वापस या नीचे कभी भी बोलिए।"
    Language.HINGLISH -> "Namaste! Main aapse poochunga kya bharna hai. Next, submit, back ya scroll kabhi bhi boliye."
}
