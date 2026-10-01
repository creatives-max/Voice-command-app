package com.voicecontrol.feature.assistant.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voicecontrol.core.accessibility.ServiceListener
import com.voicecontrol.core.ui.theme.VoiceControlTheme
import com.voicecontrol.feature.assistant.AssistantController
import com.voicecontrol.feature.assistant.OverlayEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

/** Shows the floating mic bubble while the accessibility service is running. */
@Singleton
class OverlayManager @Inject constructor(
    private val controller: AssistantController,
) : ServiceListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var window: OverlayWindow? = null
    private var effectsJob: Job? = null

    override fun onServiceConnected(service: AccessibilityService) {
        val overlay = OverlayWindow(service)
        overlay.show { onDrag ->
            val state by controller.state.collectAsStateWithLifecycle()
            VoiceControlTheme {
                OverlayContent(state, controller, onDrag)
            }
        }
        window = overlay
        effectsJob = controller.effects.onEach { effect ->
            when (effect) {
                is OverlayEffect.OpenUrl -> runCatching {
                    service.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(effect.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }.launchIn(scope)
    }

    override fun onServiceDisconnected() {
        effectsJob?.cancel()
        window?.hide()
        window = null
    }
}
