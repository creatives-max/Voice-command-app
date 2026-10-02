package com.voicecontrol.core.engine.port

/** Speech-to-text port. Android's SpeechRecognizer implements it today; a cloud STT can replace it. */
interface SpeechToText {
    /**
     * Listens for one utterance.
     * @param onPartial live partial transcripts (for the overlay caption)
     * @param onLevel normalized microphone level 0..1 (for the bubble animation)
     */
    suspend fun listen(
        request: ListenRequest,
        onPartial: (String) -> Unit = {},
        onLevel: (Float) -> Unit = {},
    ): ListenResult

    fun cancel()

    /** Stops listening and delivers what was heard so far (used when a command is recognized early). */
    fun stopListening() = cancel()
}

/**
 * Detects the user starting to speak while the assistant is talking (barge-in).
 * [awaitSpeech] suspends until speech is detected; it is cancelled when the assistant finishes talking.
 */
fun interface SpeechDetector {
    suspend fun awaitSpeech()
}

data class ListenRequest(
    val languageTag: String,
    val preferOffline: Boolean = false,
    /** Words that bias recognition (field labels, button names). */
    val biasPhrases: List<String> = emptyList(),
)

sealed interface ListenResult {
    data class Heard(val text: String, val alternatives: List<String> = emptyList(), val confidence: Float? = null) : ListenResult
    data object NoMatch : ListenResult
    data class Error(val message: String, val recoverable: Boolean, val cause: ErrorCause = ErrorCause.OTHER) : ListenResult

    enum class ErrorCause {
        OTHER,
        /** The recognition service needs the internet and can't reach it. */
        NETWORK,
        /** The language isn't available (for example its offline pack isn't downloaded). */
        LANGUAGE_UNAVAILABLE,
        /** The microphone is not allowed (no permission, or blocked while VoiceControl is in the background). */
        PERMISSION,
    }
}

/** Text-to-speech port. */
interface TextToSpeech {
    /** Speaks [text] and suspends until finished. Returns false when speech failed. */
    suspend fun speak(text: String, languageTag: String, rate: Float = 1f): Boolean

    fun stop()
}

/** How a language pack download was started. */
enum class PackDownload {
    /** The download runs in the background. */
    STARTED,
    /** The system settings screen for it was opened (older Android versions). */
    OPENED_SETTINGS,
    UNSUPPORTED,
}

/** Offline speech and voice packs of the phone. */
interface LanguagePacks {
    suspend fun status(): List<com.voicecontrol.core.engine.LanguagePack>
    suspend fun downloadSpeech(language: com.voicecontrol.core.model.Language): PackDownload
    fun installVoice(language: com.voicecontrol.core.model.Language): PackDownload
}
