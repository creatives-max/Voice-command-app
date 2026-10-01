package com.voicecontrol.core.voice

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.UtteranceProgressListener
import com.voicecontrol.core.engine.port.TextToSpeech
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import android.speech.tts.TextToSpeech as PlatformTts

/** [TextToSpeech] backed by the platform TTS engine, with per-utterance suspension. */
@Singleton
class AndroidTextToSpeech @Inject constructor(
    @ApplicationContext private val context: Context,
) : TextToSpeech {

    private val initMutex = Mutex()
    private var tts: PlatformTts? = null
    private var currentTag: String? = null
    private val pending = ConcurrentHashMap<String, Continuation<Outcome>>()

    private enum class Outcome { DONE, ERROR, STOPPED }

    /** The voice picked by [useBestVoice] (null = the engine's default), and voices that failed to speak. */
    private var chosenVoice: String? = null
    private val brokenVoices = mutableSetOf<String>()

    private suspend fun engine(): PlatformTts? = initMutex.withLock {
        tts?.let { return it }
        val ready = CompletableDeferred<Boolean>()
        val created = PlatformTts(context) { status -> ready.complete(status == PlatformTts.SUCCESS) }
        if (!ready.await()) {
            created.shutdown()
            return null
        }
        created.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        created.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = complete(utteranceId, Outcome.DONE)

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = complete(utteranceId, Outcome.ERROR)
            override fun onError(utteranceId: String?, errorCode: Int) = complete(utteranceId, Outcome.ERROR)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = complete(utteranceId, Outcome.STOPPED)
        })
        tts = created
        created
    }

    private fun complete(id: String?, outcome: Outcome) {
        id ?: return
        pending.remove(id)?.resume(outcome)
    }

    override suspend fun speak(text: String, languageTag: String, rate: Float): Boolean {
        if (text.isBlank()) return true
        val engine = engine() ?: return false
        if (currentTag != languageTag) {
            selectLanguage(engine, languageTag)
            currentTag = languageTag
        }
        engine.setSpeechRate(rate.coerceIn(0.5f, 2f))
        when (utter(engine, text)) {
            Outcome.DONE -> return true
            Outcome.STOPPED -> return false
            Outcome.ERROR -> Unit
        }
        // The chosen voice may not work on this phone (data missing, engine quirk): never stay silent —
        // go back to the engine's default voice for the language, don't pick that voice again, and retry.
        val chosen = chosenVoice ?: return false
        brokenVoices += chosen
        selectLanguage(engine, languageTag)
        return utter(engine, text) == Outcome.DONE
    }

    private suspend fun utter(engine: PlatformTts, text: String): Outcome {
        val id = UUID.randomUUID().toString()
        return suspendCancellableCoroutine { cont ->
            pending[id] = cont
            cont.invokeOnCancellation {
                pending.remove(id)
                engine.stop()
            }
            if (engine.speak(text, PlatformTts.QUEUE_FLUSH, null, id) != PlatformTts.SUCCESS) {
                pending.remove(id)
                if (cont.isActive) cont.resume(Outcome.ERROR)
            }
        }
    }

    private fun selectLanguage(engine: PlatformTts, tag: String) {
        val wanted = Locale.forLanguageTag(tag)
        val result = engine.setLanguage(wanted)
        var used = tag
        if (result == PlatformTts.LANG_MISSING_DATA || result == PlatformTts.LANG_NOT_SUPPORTED) {
            // Fall back to the base language (e.g. "hi"), then to Indian English.
            val base = engine.setLanguage(Locale.Builder().setLanguage(wanted.language).build())
            used = wanted.language
            if (base == PlatformTts.LANG_MISSING_DATA || base == PlatformTts.LANG_NOT_SUPPORTED) {
                engine.setLanguage(Locale.forLanguageTag("en-IN"))
                used = "en-IN"
            }
        }
        chosenVoice = null
        useBestVoice(engine, used)
    }

    /** The default voice for a language is often a basic one; switch to the best installed voice for it. */
    private fun useBestVoice(engine: PlatformTts, tag: String) {
        runCatching {
            val voices = engine.voices.orEmpty().filter { it.name !in brokenVoices }.map { v ->
                com.voicecontrol.core.engine.VoiceCandidate(
                    name = v.name,
                    language = v.locale.language,
                    country = v.locale.country,
                    quality = v.quality,
                    latency = v.latency,
                    needsNetwork = v.isNetworkConnectionRequired,
                    notInstalled = PlatformTts.Engine.KEY_FEATURE_NOT_INSTALLED in v.features.orEmpty(),
                )
            }
            val best = com.voicecontrol.core.engine.VoicePicker.best(voices, tag) ?: return
            val voice = engine.voices.orEmpty().firstOrNull { it.name == best.name } ?: return
            if (engine.setVoice(voice) == PlatformTts.SUCCESS) chosenVoice = voice.name
        }
    }

    /** Whether the TTS engine has a voice for this language (used by Settings). */
    suspend fun isLanguageAvailable(tag: String): Boolean {
        val engine = engine() ?: return false
        return engine.isLanguageAvailable(Locale.forLanguageTag(tag)) >= PlatformTts.LANG_AVAILABLE
    }

    override fun stop() {
        tts?.stop()
        pending.keys.toList().forEach { complete(it, Outcome.STOPPED) }
    }
}
