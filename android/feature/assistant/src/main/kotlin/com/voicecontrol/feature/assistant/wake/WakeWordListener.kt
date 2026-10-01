package com.voicecontrol.feature.assistant.wake

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.PowerManager
import com.voicecontrol.core.accessibility.ServiceListener
import com.voicecontrol.core.data.settings.SettingsRepository
import com.voicecontrol.core.engine.AssistantEngine
import com.voicecontrol.core.engine.port.ListenRequest
import com.voicecontrol.core.engine.port.ListenResult
import com.voicecontrol.core.engine.port.SpeechToText
import com.voicecontrol.core.nlp.WakeWord
import com.voicecontrol.feature.assistant.AssistantController
import com.voicecontrol.feature.assistant.MicPermission
import com.voicecontrol.feature.assistant.MicrophoneForeground
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Custom wake phrase: while the accessibility service runs, the setting is on, no session is active
 * and the screen is on, listens (on-device recognition preferred) for the user's phrase and starts a
 * voice session when it is heard. Transcripts are only matched against the phrase, never kept.
 */
@Singleton
class WakeWordListener @Inject constructor(
    private val settings: SettingsRepository,
    private val stt: SpeechToText,
    private val engine: AssistantEngine,
    private val controller: AssistantController,
    private val micPermission: MicPermission,
    private val microphone: MicrophoneForeground,
) : ServiceListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onServiceConnected(service: AccessibilityService) {
        microphone.resumeWakeWord()
        val power = service.getSystemService(Context.POWER_SERVICE) as PowerManager
        scope.launch { loop(power) }
    }

    override fun onServiceDisconnected() {
        scope.coroutineContext.cancelChildren()
        microphone.setWakeListening(null)
    }

    private suspend fun loop(power: PowerManager) {
        var failures = 0
        while (scope.isActive) {
            val prefs = settings.appSettings()
            val ready = prefs.wakeWordEnabled && WakeWord.isValidPhrase(prefs.wakeWord) && !engine.isActive &&
                !microphone.wakePaused.value && power.isInteractive && micPermission.granted()
            if (!ready) {
                microphone.setWakeListening(null)
                delay(IDLE_RECHECK_MS)
                continue
            }
            microphone.setWakeListening(prefs.wakeWord)
            var heard = false
            val listening = scope.async {
                stt.listen(
                    ListenRequest(prefs.language.speechTag, preferOffline = true, biasPhrases = listOf(prefs.wakeWord)),
                    onPartial = { partial ->
                        if (!heard && WakeWord.matches(partial, prefs.wakeWord)) {
                            heard = true
                            stt.stopListening()
                        }
                    },
                )
            }
            // A session started by a tap needs the recognizer: give it up right away.
            val yieldToSession = scope.launch { engine.state.first { it.active }; listening.cancel() }
            val result = try {
                listening.await()
            } catch (e: CancellationException) {
                if (!scope.isActive) throw e
                null
            } finally {
                yieldToSession.cancel()
            }
            if (result == null) {
                microphone.setWakeListening(null)
                continue
            }
            if (heard || (result is ListenResult.Heard && WakeWord.matches(result.text, prefs.wakeWord))) {
                failures = 0
                microphone.setWakeListening(null)
                controller.startSession()
                // Let the session start before checking again.
                delay(SESSION_START_MS)
                continue
            }
            failures = if (result is ListenResult.Error) failures + 1 else 0
            // Recognizer errors back off; silence restarts listening right away.
            delay(if (failures > 0) (RETRY_MS * failures).coerceAtMost(MAX_BACKOFF_MS) else RESTART_MS)
        }
    }

    private companion object {
        const val IDLE_RECHECK_MS = 5_000L
        const val SESSION_START_MS = 3_000L
        const val RESTART_MS = 250L
        const val RETRY_MS = 2_000L
        const val MAX_BACKOFF_MS = 30_000L
    }
}
