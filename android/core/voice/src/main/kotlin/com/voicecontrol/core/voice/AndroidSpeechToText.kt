package com.voicecontrol.core.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.voicecontrol.core.engine.port.ListenRequest
import com.voicecontrol.core.engine.port.ListenResult
import com.voicecontrol.core.engine.port.SpeechToText
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * [SpeechToText] backed by the platform [SpeechRecognizer] (Google / OEM recognition service).
 * All recognizer calls happen on the main thread as the API requires.
 */
@Singleton
class AndroidSpeechToText @Inject constructor(
    @ApplicationContext private val context: Context,
) : SpeechToText {

    private var recognizer: SpeechRecognizer? = null

    override suspend fun listen(
        request: ListenRequest,
        onPartial: (String) -> Unit,
        onLevel: (Float) -> Unit,
    ): ListenResult = withContext(Dispatchers.Main) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            return@withContext ListenResult.Error("Speech recognition is not available on this device", recoverable = false)
        }
        val sr = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }
        suspendCancellableCoroutine<ListenResult> { cont ->
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit

                override fun onPartialResults(partialResults: Bundle?) {
                    partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.takeIf { it.isNotBlank() }?.let(onPartial)
                }

                override fun onResults(results: Bundle?) {
                    val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                    val scores = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                    val result = texts.firstOrNull()?.takeIf { it.isNotBlank() }
                        ?.let { ListenResult.Heard(it, texts.drop(1), scores?.firstOrNull()) }
                        ?: ListenResult.NoMatch
                    if (cont.isActive) cont.resume(result)
                }

                override fun onError(error: Int) {
                    if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT) {
                        // Recreate on next use; a busy/broken recognizer won't recover by itself.
                        recognizer?.destroy()
                        recognizer = null
                    }
                    if (cont.isActive) cont.resume(mapError(error))
                }
            })
            sr.startListening(intentFor(request))
            cont.invokeOnCancellation {
                runCatching { sr.cancel() }
            }
        }
    }

    override fun stopListening() {
        recognizer?.let { sr -> android.os.Handler(context.mainLooper).post { runCatching { sr.stopListening() } } }
    }

    override fun cancel() {
        recognizer?.let { sr -> android.os.Handler(context.mainLooper).post { runCatching { sr.cancel() } } }
    }

    private fun intentFor(request: ListenRequest): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, request.languageTag)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, request.languageTag)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, request.preferOffline)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_500L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1_200L)
    }

    companion object {
        fun mapError(error: Int): ListenResult = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> ListenResult.NoMatch
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                ListenResult.Error("Microphone permission is required. Open VoiceControl to allow it.", recoverable = false)
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
                ListenResult.Error("Speech service is unreachable", recoverable = true)
            else -> ListenResult.Error("Speech recognizer error $error", recoverable = true)
        }
    }
}
