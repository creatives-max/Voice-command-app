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
    val busy: Boolean = false,
    val testingWake: Boolean = false,
    val wakeState: com.voicecontrol.core.data.settings.WakeState = com.voicecontrol.core.data.settings.WakeState.OFF,
)

/** One line under the wake phrase setting: what the listener is doing, and what to do when it can't. */
internal fun wakeStateText(state: com.voicecontrol.core.data.settings.WakeState): String = when (state) {
    com.voicecontrol.core.data.settings.WakeState.OFF -> "Off"
    com.voicecontrol.core.data.settings.WakeState.LISTENING -> "Listening for your wake phrase"
    com.voicecontrol.core.data.settings.WakeState.SCREEN_OFF -> "Waiting: listens while the screen is on"
    com.voicecontrol.core.data.settings.WakeState.SESSION -> "Paused during a voice session"
    com.voicecontrol.core.data.settings.WakeState.PAUSED -> "Paused from the notification; it resumes now that VoiceControl is open"
    com.voicecontrol.core.data.settings.WakeState.NO_MIC_PERMISSION -> "Needs the microphone permission"
    com.voicecontrol.core.data.settings.WakeState.MIC_BLOCKED ->
        "Android stopped the microphone in the background. Open VoiceControl once after restarting the phone; keep battery set to Unrestricted"
    com.voicecontrol.core.data.settings.WakeState.RECOGNIZER_ERROR ->
        "The phone's speech service keeps failing. Check that Google speech services are installed and updated"
    com.voicecontrol.core.data.settings.WakeState.ACCESSIBILITY_OFF -> "Turn on VoiceControl in Accessibility settings"
}

sealed interface SettingsIntent {
    data class SetLanguage(val language: Language) : SettingsIntent
    data class SetSpeechRate(val rate: Float) : SettingsIntent
    data class SetThemeMode(val mode: com.voicecontrol.core.model.ThemeMode) : SettingsIntent
    data class Toggle(val option: Option, val enabled: Boolean) : SettingsIntent
    data class EditBackendUrl(val value: String) : SettingsIntent
    data class EditDashboardUrl(val value: String) : SettingsIntent
    data object SaveUrls : SettingsIntent
    data class EditDeviceName(val value: String) : SettingsIntent
    data object SaveDeviceName : SettingsIntent
    data class EditWakeWord(val value: String) : SettingsIntent
    data object SaveWakeWord : SettingsIntent
    /** Listen once and say whether that would wake VoiceControl. */
    data object TestWakeWord : SettingsIntent
    data object TestVoice : SettingsIntent
    /** Only sent after the fingerprint/face/screen lock check succeeded. */
    data class SetAppLock(val enabled: Boolean) : SettingsIntent
    data class SetLockTimeout(val seconds: Int) : SettingsIntent
    data class ExportData(val target: android.net.Uri) : SettingsIntent
    data object WipePhone : SettingsIntent
}

enum class Option { TRANSLITERATE, CONFIRM_VALUES, ASK_BEFORE_SUBMIT, SKIP_FILLED, LOCAL_ONLY, SAVE_HISTORY, SHOW_OVERLAY, VISION_FALLBACK, AUTO_START, REMOTE_RUNS, USE_TEMPLATES, BARGE_IN, CONFIRM_LOW_CONFIDENCE, WAKE_WORD, CONFIRM_DESTRUCTIVE, CRASH_REPORTS, OFFLINE_SPEECH, REMEMBER_ANSWERS, ANNOUNCE_CALLS }

sealed interface SettingsEffect {
    data class Message(val text: String) : SettingsEffect
    data object PhoneWiped : SettingsEffect
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
    Option.ANNOUNCE_CALLS -> copy(announceCalls = enabled)
    Option.CONFIRM_DESTRUCTIVE -> copy(confirmDestructive = enabled)
    Option.CRASH_REPORTS -> copy(crashReports = enabled)
    Option.OFFLINE_SPEECH -> copy(offlineSpeech = enabled)
    Option.REMEMBER_ANSWERS -> copy(rememberAnswers = enabled)
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
    Option.ANNOUNCE_CALLS -> announceCalls
    Option.CONFIRM_DESTRUCTIVE -> confirmDestructive
    Option.CRASH_REPORTS -> crashReports
    Option.OFFLINE_SPEECH -> offlineSpeech
    Option.REMEMBER_ANSWERS -> rememberAnswers
}

/** What to tell the user after testing their wake phrase. */
fun wakeTestMessage(result: com.voicecontrol.core.engine.port.ListenResult, phrase: String): String = when (result) {
    is com.voicecontrol.core.engine.port.ListenResult.Heard ->
        if ((listOf(result.text) + result.alternatives).any { com.voicecontrol.core.nlp.WakeWord.matches(it, phrase) }) {
            "Heard “${result.text}”. That wakes VoiceControl."
        } else {
            "Heard “${result.text}”, which doesn't match “$phrase”. Say it clearly, or choose a longer phrase with unusual words."
        }
    com.voicecontrol.core.engine.port.ListenResult.NoMatch -> "Didn't hear anything. Tap Test and say your wake phrase."
    is com.voicecontrol.core.engine.port.ListenResult.Error -> "Couldn't listen: ${result.message}"
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
