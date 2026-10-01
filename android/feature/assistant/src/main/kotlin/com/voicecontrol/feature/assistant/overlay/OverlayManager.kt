package com.voicecontrol.feature.assistant.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voicecontrol.core.accessibility.ServiceListener
import com.voicecontrol.core.data.automation.RemoteRunRepository
import com.voicecontrol.core.data.settings.SettingsRepository
import com.voicecontrol.core.engine.port.FlowSource
import com.voicecontrol.core.engine.port.ScreenGateway
import com.voicecontrol.core.ui.theme.VoiceControlTheme
import com.voicecontrol.feature.assistant.AssistantController
import com.voicecontrol.feature.assistant.OverlayEffect
import com.voicecontrol.feature.assistant.MicrophoneForeground
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shows the floating mic bubble while the accessibility service runs, keeps the microphone
 * foreground service in sync with the session, and auto-starts sessions on apps with saved flows.
 */
@Singleton
class OverlayManager @Inject constructor(
    private val controller: AssistantController,
    private val settings: SettingsRepository,
    private val screen: ScreenGateway,
    private val flows: FlowSource,
    private val remoteRuns: RemoteRunRepository,
    private val microphone: MicrophoneForeground,
) : ServiceListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var window: OverlayWindow? = null
    private var service: AccessibilityService? = null
    private var autoStartJob: Job? = null

    override fun onServiceConnected(service: AccessibilityService) {
        this.service = service
        val overlay = OverlayWindow(service)
        window = overlay

        settings.settings.map { it.showOverlay }.distinctUntilChanged().onEach { show ->
            if (show) {
                overlay.show { onDrag ->
                    val state by controller.state.collectAsStateWithLifecycle()
                    VoiceControlTheme { OverlayContent(state, controller, onDrag) }
                }
            } else {
                overlay.hide()
            }
        }.launchIn(scope)

        microphone.attach(service)
        controller.state.map { it.sessionActive }.distinctUntilChanged().onEach { active ->
            microphone.setSessionActive(active)
        }.launchIn(scope)

        controller.effects.onEach { effect ->
            when (effect) {
                is OverlayEffect.OpenUrl -> runCatching {
                    service.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(effect.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                OverlayEffect.RequestMicPermission -> runCatching {
                    service.packageManager.getLaunchIntentForPackage(service.packageName)
                        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ?.let(service::startActivity)
                }
            }
        }.launchIn(scope)
    }

    override fun onForegroundAppChanged(packageName: String) {
        autoStartJob?.cancel()
        autoStartJob = scope.launch {
            val prefs = settings.appSettings()
            if (!prefs.autoStartWithFlow || controller.sessionActive) return@launch
            // An app-open trigger runs its own flow for this app (see RemoteRunCoordinator).
            if (remoteRuns.triggerFor(packageName) != null) return@launch
            delay(AUTO_START_SETTLE_MS)
            val snapshot = screen.capture() ?: return@launch
            if (snapshot.packageName == packageName && flows.flowFor(snapshot) != null) controller.startSession()
        }
    }

    override fun onServiceDisconnected() {
        scope.coroutineContext.cancelChildren()
        window?.hide()
        window = null
        microphone.detach()
        service = null
    }

    private companion object {
        const val AUTO_START_SETTLE_MS = 1_500L
    }
}
