package com.voicecontrol.core.voice

import android.content.Context
import android.content.Intent
import android.os.Build
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
 * With [ListenRequest.preferOffline] it uses the on-device recognizer (Android 12+) so speech works
 * without internet; languages whose offline pack is missing fall back to the regular recognizer
 * (asked to prefer offline). All recognizer calls happen on the main thread as the API requires.
 */
@Singleton
class AndroidSpeechToText @Inject constructor(
    @ApplicationContext private val context: Context,
) : SpeechToText {

    private var recognizer: SpeechRecognizer? = null
    private var onDevice: SpeechRecognizer? = null
    /** Languages the on-device recognizer said it can't do (pack missing); not retried this run. */
    private val noOnDevicePack = mutableSetOf<String>()

    private fun useOnDevice(request: ListenRequest): Boolean =
        request.preferOffline && request.languageTag !in noOnDevicePack &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    override suspend fun listen(
        request: ListenRequest,
        onPartial: (String) -> Unit,
        onLevel: (Float) -> Unit,
    ): ListenResult = withContext(Dispatchers.Main) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            return@withContext ListenResult.Error("Speech recognition is not available on this device", recoverable = false)
        }
        val local = useOnDevice(request)
        val sr = if (local && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            onDevice ?: SpeechRecognizer.createOnDeviceSpeechRecognizer(context).also { onDevice = it }
        } else {
            recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }
        }
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
                        if (local) {
                            onDevice?.destroy()
                            onDevice = null
                        } else {
                            recognizer?.destroy()
                            recognizer = null
                        }
                    }
                    if (local && (error == ERROR_LANGUAGE_NOT_SUPPORTED || error == ERROR_LANGUAGE_UNAVAILABLE)) noOnDevicePack += request.languageTag
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
        listOfNotNull(recognizer, onDevice).forEach { sr -> android.os.Handler(context.mainLooper).post { runCatching { sr.stopListening() } } }
    }

    override fun cancel() {
        listOfNotNull(recognizer, onDevice).forEach { sr -> android.os.Handler(context.mainLooper).post { runCatching { sr.cancel() } } }
    }

    /** A language pack was downloaded: try the on-device recognizer for every language again. */
    fun packsChanged() {
        noOnDevicePack.clear()
    }

    private fun intentFor(request: ListenRequest): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, request.languageTag)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, request.languageTag)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, request.preferOffline)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        // Unhurried speakers pause mid-sentence; don't cut them off.
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2_000L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1_500L)
        // The names on screen (buttons, fields, apps) are what people say most: help the recognizer hear them.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU && request.biasPhrases.isNotEmpty()) {
            putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(request.biasPhrases))
        }
    }

    companion object {
        /** SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED / ERROR_LANGUAGE_UNAVAILABLE (Android 12+). */
        const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        const val ERROR_LANGUAGE_UNAVAILABLE = 13

        fun mapError(error: Int): ListenResult = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> ListenResult.NoMatch
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                ListenResult.Error("Microphone permission is required. Open VoiceControl to allow it.", recoverable = false)
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
                ListenResult.Error("Speech service is unreachable", recoverable = true, cause = ListenResult.ErrorCause.NETWORK)
            ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE ->
                ListenResult.Error("This language isn't available for speech on this phone", recoverable = true, cause = ListenResult.ErrorCause.LANGUAGE_UNAVAILABLE)
            else -> ListenResult.Error("Speech recognizer error $error", recoverable = true)
        }
    }
}
