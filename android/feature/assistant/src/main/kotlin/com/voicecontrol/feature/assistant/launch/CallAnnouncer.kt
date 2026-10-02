package com.voicecontrol.feature.assistant.launch

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.view.accessibility.AccessibilityEvent
import com.voicecontrol.core.accessibility.ServiceListener
import com.voicecontrol.core.data.settings.SettingsRepository
import com.voicecontrol.core.engine.AssistantEngine
import com.voicecontrol.core.engine.Phrases
import com.voicecontrol.core.engine.port.TextToSpeech
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * When the phone rings, says who is calling ("Rahul ka call aa raha hai"), from the incoming-call
 * notification the phone app posts. Once per caller per ring; off in Settings ("Say who is calling").
 */
@Singleton
class CallAnnouncer @Inject constructor(
    private val settings: SettingsRepository,
    private val tts: TextToSpeech,
    private val engine: AssistantEngine,
) : ServiceListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var lastCaller: String? = null
    private var lastAt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) return
        val n = event.parcelableData as? Notification ?: return
        if (n.category != Notification.CATEGORY_CALL) return
        val caller = n.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val now = System.currentTimeMillis()
        // The phone app updates its notification several times per ring.
        if (caller == lastCaller && now - lastAt < REPEAT_GAP_MS) return
        lastCaller = caller
        lastAt = now
        if (engine.isActive) return
        scope.launch {
            val prefs = settings.appSettings()
            if (!prefs.announceCalls) return@launch
            runCatching { tts.speak(Phrases(prefs.language).incomingCall(caller.take(MAX_NAME)), prefs.language.voiceTag, prefs.speechRate) }
        }
    }

    override fun onServiceConnected(service: AccessibilityService) {
        lastCaller = null
    }

    override fun onServiceDisconnected() {
        scope.coroutineContext.cancelChildren()
    }

    private companion object {
        const val REPEAT_GAP_MS = 30_000L
        const val MAX_NAME = 40
    }
}
