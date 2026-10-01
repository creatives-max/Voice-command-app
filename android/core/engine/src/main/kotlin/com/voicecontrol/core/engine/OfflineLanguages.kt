package com.voicecontrol.core.engine

import com.voicecontrol.core.model.Language

/** On-device speech recognition for one language. */
enum class SpeechPackState {
    /** Downloaded: recognition works without internet. */
    INSTALLED,
    DOWNLOADING,
    /** Can be downloaded. */
    AVAILABLE,
    /** This phone's recognition service has no offline model for the language. */
    UNSUPPORTED,
    /** The phone can't say (Android 12 and older). */
    UNKNOWN,
}

/** The text-to-speech voice for one language. */
enum class VoiceState { INSTALLED, NEEDS_DOWNLOAD, UNSUPPORTED, UNKNOWN }

data class LanguagePack(val language: Language, val speech: SpeechPackState, val voice: VoiceState) {
    /** Both listening and speaking work without internet. */
    val worksOffline: Boolean get() = speech == SpeechPackState.INSTALLED && voice == VoiceState.INSTALLED
    val canDownloadSpeech: Boolean get() = speech == SpeechPackState.AVAILABLE || speech == SpeechPackState.UNKNOWN
    val canDownloadVoice: Boolean get() = voice == VoiceState.NEEDS_DOWNLOAD || voice == VoiceState.UNKNOWN
}

/** What the phone's recognition service reports (BCP-47 tags such as "hi-IN" or "hi"). */
data class RecognitionSupportInfo(
    val installed: List<String> = emptyList(),
    val pending: List<String> = emptyList(),
    val supported: List<String> = emptyList(),
)

/** Offline speech packs for VoiceControl's languages. */
object OfflineLanguages {

    /** "hi-IN" matches "hi", "hi_IN" and "hi-IN", not "hi-Latn"-less mismatched regions like "hi-US". */
    fun matches(available: String, wanted: String): Boolean {
        val a = available.replace('_', '-').lowercase().split('-')
        val w = wanted.replace('_', '-').lowercase().split('-')
        if (a.first() != w.first()) return false
        val aRegion = a.drop(1).firstOrNull { it.length == 2 }
        val wRegion = w.drop(1).firstOrNull { it.length == 2 }
        return aRegion == null || wRegion == null || aRegion == wRegion
    }

    fun speechState(language: Language, info: RecognitionSupportInfo?): SpeechPackState {
        info ?: return SpeechPackState.UNKNOWN
        val tag = language.speechTag
        return when {
            info.installed.any { matches(it, tag) } -> SpeechPackState.INSTALLED
            info.pending.any { matches(it, tag) } -> SpeechPackState.DOWNLOADING
            info.supported.any { matches(it, tag) } -> SpeechPackState.AVAILABLE
            else -> SpeechPackState.UNSUPPORTED
        }
    }

    fun packs(info: RecognitionSupportInfo?, voice: (Language) -> VoiceState): List<LanguagePack> =
        Language.entries.map { LanguagePack(it, speechState(it, info), voice(it)) }

    fun summary(packs: List<LanguagePack>): String {
        val ready = packs.filter { it.worksOffline }
        return when {
            packs.isEmpty() -> "Checking offline languages…"
            ready.isEmpty() -> "No language works offline yet. Download one below."
            ready.size == packs.size -> "All languages work offline."
            else -> "Works offline in ${ready.joinToString { it.language.nativeName }}."
        }
    }
}
