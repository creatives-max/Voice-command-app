package com.voicecontrol.feature.assistant

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the microphone foreground service in step with what needs the microphone: an active voice
 * session and/or listening for the wake phrase. One service, one notification.
 */
@Singleton
class MicrophoneForeground @Inject constructor() {
    private var context: Context? = null
    private var sessionActive = false
    private var wakePhrase: String? = null
    private var running: String? = null

    private val paused = MutableStateFlow(false)
    /** The user tapped Stop on the wake notification; listening resumes next time VoiceControl starts. */
    val wakePaused: StateFlow<Boolean> = paused.asStateFlow()

    @Synchronized
    fun attach(context: Context) {
        this.context = context
        apply()
    }

    @Synchronized
    fun detach() {
        context?.let(VoiceSessionService::stop)
        context = null
        running = null
        sessionActive = false
        wakePhrase = null
    }

    @Synchronized
    fun setSessionActive(active: Boolean) {
        sessionActive = active
        apply()
    }

    @Synchronized
    fun setWakeListening(phrase: String?) {
        wakePhrase = phrase
        apply()
    }

    fun pauseWakeWord() {
        paused.value = true
        setWakeListening(null)
    }

    fun resumeWakeWord() {
        paused.value = false
    }

    private fun apply() {
        val ctx = context ?: return
        val mode = when {
            sessionActive -> MODE_SESSION
            wakePhrase != null -> MODE_WAKE + wakePhrase
            else -> null
        }
        if (mode == running) return
        running = mode
        when {
            mode == null -> VoiceSessionService.stop(ctx)
            sessionActive -> VoiceSessionService.start(ctx)
            else -> VoiceSessionService.start(ctx, wakePhrase)
        }
    }

    private companion object {
        const val MODE_SESSION = "session"
        const val MODE_WAKE = "wake:"
    }
}
