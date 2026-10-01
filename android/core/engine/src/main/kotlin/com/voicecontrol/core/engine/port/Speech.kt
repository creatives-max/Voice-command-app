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
    data class Error(val message: String, val recoverable: Boolean) : ListenResult
}

/** Text-to-speech port. */
interface TextToSpeech {
    /** Speaks [text] and suspends until finished. Returns false when speech failed. */
    suspend fun speak(text: String, languageTag: String, rate: Float = 1f): Boolean

    fun stop()
}
