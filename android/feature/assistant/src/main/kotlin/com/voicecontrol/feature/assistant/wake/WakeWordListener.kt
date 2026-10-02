package com.voicecontrol.feature.assistant.wake

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.PowerManager
import com.voicecontrol.core.accessibility.ServiceListener
import com.voicecontrol.core.data.settings.SettingsRepository
import com.voicecontrol.core.data.settings.WakeState
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
    private val status: com.voicecontrol.core.data.settings.WakeWordStatus,
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
        status.set(WakeState.ACCESSIBILITY_OFF)
    }

    private suspend fun loop(power: PowerManager) {
        var failures = 0
        while (scope.isActive) {
            val prefs = settings.appSettings()
            val off = when {
                !prefs.wakeWordEnabled || !WakeWord.isValidPhrase(prefs.wakeWord) -> WakeState.OFF
                microphone.wakePaused.value -> WakeState.PAUSED
                !micPermission.granted() -> WakeState.NO_MIC_PERMISSION
                else -> null
            }
            if (off != null) {
                status.set(off)
                microphone.setWakeListening(null)
                delay(IDLE_RECHECK_MS)
                continue
            }
            // During a session or with the screen off, keep the microphone service (Android 14+ won't let it be
            // started again from the background) and just don't listen.
            microphone.setWakeListening(prefs.wakeWord)
            if (engine.isActive || !power.isInteractive) {
                status.set(if (engine.isActive) WakeState.SESSION else WakeState.SCREEN_OFF)
                delay(BUSY_RECHECK_MS)
                continue
            }
            when {
                com.voicecontrol.feature.assistant.VoiceSessionService.startFailed -> status.set(WakeState.MIC_BLOCKED)
                failures == 0 -> status.set(WakeState.LISTENING)
            }
            var heard = false
            var lastPartial = ""
            var stopLater: kotlinx.coroutines.Job? = null
            val listening = scope.async {
                stt.listen(
                    ListenRequest(prefs.language.speechTag, preferOffline = true, biasPhrases = listOf(prefs.wakeWord)),
                    onPartial = { partial ->
                        lastPartial = partial
                        if (heard || WakeWord.matches(partial, prefs.wakeWord)) {
                            heard = true
                            // Let the user finish a request said in the same breath ("voice control, YouTube kholo"):
                            // stop once the words stop changing.
                            stopLater?.cancel()
                            stopLater = scope.launch {
                                delay(REQUEST_SETTLE_MS)
                                stt.stopListening()
                            }
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
                stopLater?.cancel()
            }
            // The microphone service stays up and switches to the session (stopping it here would need a new
            // start from the background, which Android 14+ refuses).
            if (result == null) continue
            val finalText = (result as? ListenResult.Heard)?.text ?: lastPartial
            if (heard || WakeWord.matches(finalText, prefs.wakeWord)) {
                failures = 0
                controller.startSession(WakeWord.after(finalText, prefs.wakeWord) ?: WakeWord.after(lastPartial, prefs.wakeWord))
                // Let the session start before checking again.
                delay(SESSION_START_MS)
                continue
            }
            failures = if (result is ListenResult.Error) failures + 1 else 0
            if (result is ListenResult.Error) {
                when {
                    result.cause == ListenResult.ErrorCause.PERMISSION -> status.set(WakeState.MIC_BLOCKED)
                    failures >= ERRORS_TO_REPORT -> status.set(WakeState.RECOGNIZER_ERROR)
                }
            }
            // Recognizer errors back off; silence restarts listening right away.
            delay(if (failures > 0) (RETRY_MS * failures).coerceAtMost(MAX_BACKOFF_MS) else RESTART_MS)
        }
    }

    private companion object {
        const val IDLE_RECHECK_MS = 5_000L
        const val BUSY_RECHECK_MS = 1_000L
        const val ERRORS_TO_REPORT = 3
        const val SESSION_START_MS = 3_000L
        /** After the wake phrase, how long the words must stay unchanged before listening stops. */
        const val REQUEST_SETTLE_MS = 1_200L
        const val RESTART_MS = 250L
        const val RETRY_MS = 2_000L
        const val MAX_BACKOFF_MS = 30_000L
    }
}
