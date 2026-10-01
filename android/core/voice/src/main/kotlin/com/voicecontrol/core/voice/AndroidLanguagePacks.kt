package com.voicecontrol.core.voice

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.provider.Settings
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import com.voicecontrol.core.engine.LanguagePack
import com.voicecontrol.core.engine.OfflineLanguages
import com.voicecontrol.core.engine.RecognitionSupportInfo
import com.voicecontrol.core.engine.VoiceState
import com.voicecontrol.core.engine.port.LanguagePacks
import com.voicecontrol.core.engine.port.PackDownload
import com.voicecontrol.core.model.Language
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import android.speech.tts.TextToSpeech as PlatformTts

/**
 * Offline speech packs: on-device recognition models (Android 13+ reports and downloads them per
 * language; older versions open the system's voice input settings) and text-to-speech voices.
 */
@Singleton
class AndroidLanguagePacks @Inject constructor(
    @ApplicationContext private val context: Context,
    private val stt: AndroidSpeechToText,
) : LanguagePacks {

    override suspend fun status(): List<LanguagePack> {
        val support = recognitionSupport()
        val voices = voiceStates()
        stt.packsChanged()
        return OfflineLanguages.packs(support) { voices[it] ?: VoiceState.UNKNOWN }
    }

    private fun recognitionIntent(language: Language? = null) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        language?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it.speechTag) }
    }

    private suspend fun recognitionSupport(): RecognitionSupportInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        return withContext(Dispatchers.Main) {
            if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) return@withContext RecognitionSupportInfo()
            val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            try {
                withTimeoutOrNull(SUPPORT_TIMEOUT_MS) { checkSupport(recognizer) }
            } finally {
                recognizer.destroy()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun checkSupport(recognizer: SpeechRecognizer): RecognitionSupportInfo? = suspendCancellableCoroutine { cont ->
        recognizer.checkRecognitionSupport(
            recognitionIntent(),
            context.mainExecutor,
            object : RecognitionSupportCallback {
                override fun onSupportResult(support: RecognitionSupport) {
                    if (cont.isActive) {
                        cont.resume(RecognitionSupportInfo(support.installedOnDeviceLanguages, support.pendingOnDeviceLanguages, support.supportedOnDeviceLanguages))
                    }
                }

                override fun onError(error: Int) {
                    if (cont.isActive) cont.resume(null)
                }
            },
        )
    }

    /** Which languages have a voice that speaks without internet. */
    private suspend fun voiceStates(): Map<Language, VoiceState> = withContext(Dispatchers.Main) {
        val ready = CompletableDeferred<Boolean>()
        val tts = PlatformTts(context) { status -> ready.complete(status == PlatformTts.SUCCESS) }
        try {
            if (withTimeoutOrNull(SUPPORT_TIMEOUT_MS) { ready.await() } != true) return@withContext emptyMap()
            val voices = runCatching { tts.voices.orEmpty() }.getOrDefault(emptySet())
            Language.entries.associateWith { language ->
                val locale = Locale.forLanguageTag(language.voiceTag)
                val local = voices.any { v ->
                    v.locale.language == locale.language && !v.isNetworkConnectionRequired &&
                        PlatformTts.Engine.KEY_FEATURE_NOT_INSTALLED !in v.features.orEmpty()
                }
                when {
                    local -> VoiceState.INSTALLED
                    else -> when (runCatching { tts.isLanguageAvailable(locale) }.getOrDefault(PlatformTts.LANG_NOT_SUPPORTED)) {
                        PlatformTts.LANG_NOT_SUPPORTED -> VoiceState.UNSUPPORTED
                        else -> VoiceState.NEEDS_DOWNLOAD
                    }
                }
            }
        } finally {
            tts.shutdown()
        }
    }

    override suspend fun downloadSpeech(language: Language): PackDownload = withContext(Dispatchers.Main) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            val started = runCatching { recognizer.triggerModelDownload(recognitionIntent(language)) }.isSuccess
            // The recognition service downloads in the background; release our handle a little later.
            Handler(context.mainLooper).postDelayed({ recognizer.destroy() }, RELEASE_DELAY_MS)
            stt.packsChanged()
            if (started) return@withContext PackDownload.STARTED
        }
        open(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
    }

    override fun installVoice(language: Language): PackDownload = open(Intent(PlatformTts.Engine.ACTION_INSTALL_TTS_DATA))

    private fun open(intent: Intent): PackDownload = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        PackDownload.OPENED_SETTINGS
    } catch (e: ActivityNotFoundException) {
        PackDownload.UNSUPPORTED
    } catch (e: SecurityException) {
        PackDownload.UNSUPPORTED
    }

    private companion object {
        const val SUPPORT_TIMEOUT_MS = 5_000L
        const val RELEASE_DELAY_MS = 10_000L
    }
}
