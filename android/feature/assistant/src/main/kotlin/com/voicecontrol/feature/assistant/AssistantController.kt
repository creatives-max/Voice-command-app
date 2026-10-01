package com.voicecontrol.feature.assistant

import com.voicecontrol.core.engine.AssistantEngine
import com.voicecontrol.core.engine.EngineState
import com.voicecontrol.core.engine.EngineStatus
import com.voicecontrol.core.engine.port.FlowLauncher
import com.voicecontrol.core.engine.port.LaunchResult
import com.voicecontrol.core.engine.port.ScreenGateway
import com.voicecontrol.core.model.FlowDefinition
import com.voicecontrol.core.model.ActionResult
import com.voicecontrol.core.model.ElementKind
import com.voicecontrol.core.model.ScreenAction
import com.voicecontrol.core.model.ScreenElement
import com.voicecontrol.core.model.ScrollDirection
import com.voicecontrol.feature.assistant.overlay.BubbleMode
import com.voicecontrol.feature.assistant.overlay.OverlayActions
import com.voicecontrol.feature.assistant.overlay.OverlayUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** One-off requests from the controller that need an Android context (handled by the overlay manager). */
sealed interface OverlayEffect {
    data class OpenUrl(val url: String) : OverlayEffect
    /** Microphone permission is missing: open the app so the user can grant it. */
    data object RequestMicPermission : OverlayEffect
}

/** Checks RECORD_AUDIO; abstracted for tests. */
fun interface MicPermission {
    fun granted(): Boolean
}

/**
 * Owns the overlay's state. Tap = start/stop the voice session; long-press = touch panel that lists
 * on-screen elements and acts on them directly.
 */
@Singleton
class AssistantController @Inject constructor(
    private val screen: ScreenGateway,
    private val engine: AssistantEngine,
    private val micPermission: MicPermission,
) : OverlayActions, FlowLauncher {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Touch-panel and transient-caption state that is not part of the voice engine. */
    private data class LocalState(
        val panelOpen: Boolean = false,
        val panelElements: List<ScreenElement> = emptyList(),
        val flashCaption: String? = null,
        val flashMode: BubbleMode? = null,
        val visible: Boolean = true,
    )

    private val local = MutableStateFlow(LocalState())

    val state: StateFlow<OverlayUiState> = combine(engine.state, local) { e, l -> toUi(e, l) }
        .stateIn(scope, SharingStarted.Eagerly, OverlayUiState())

    private val _effects = MutableSharedFlow<OverlayEffect>(extraBufferCapacity = 8)
    val effects: SharedFlow<OverlayEffect> = _effects.asSharedFlow()

    private var captionJob: Job? = null

    val sessionActive: Boolean get() = engine.isActive

    fun setVisible(visible: Boolean) = local.update { it.copy(visible = visible) }

    override fun onMicTap() {
        if (engine.isActive) {
            engine.stop()
            return
        }
        if (!micPermission.granted()) {
            flashCaption(MIC_PERMISSION_TEXT, BubbleMode.ERROR)
            _effects.tryEmit(OverlayEffect.RequestMicPermission)
            return
        }
        local.update { it.copy(panelOpen = false, flashCaption = null, flashMode = null) }
        engine.start()
    }

    /** Starts a session without a tap (auto-start when an app with a saved flow opens). */
    fun startSession() {
        if (!engine.isActive && micPermission.granted()) engine.start()
    }

    /** Runs [flow] now (opening its app first). */
    override fun launch(flow: FlowDefinition): LaunchResult {
        if (engine.isActive) return LaunchResult.BUSY
        if (!screen.isAvailable.value) return LaunchResult.SERVICE_OFF
        if (!micPermission.granted()) {
            flashCaption(MIC_PERMISSION_TEXT, BubbleMode.ERROR)
            _effects.tryEmit(OverlayEffect.RequestMicPermission)
            return LaunchResult.NO_MIC_PERMISSION
        }
        local.update { it.copy(panelOpen = false, flashCaption = null, flashMode = null) }
        engine.start(flow)
        return LaunchResult.STARTED
    }

    override fun onMicLongPress() {
        if (local.value.panelOpen) {
            onClosePanel()
            return
        }
        scope.launch {
            val snapshot = screen.capture()
            local.update { it.copy(panelOpen = true, panelElements = snapshot?.elements.orEmpty()) }
        }
    }

    override fun onElementTap(element: ScreenElement) {
        val action = when {
            element.kind == ElementKind.TEXT_FIELD -> ScreenAction.Focus(element.id)
            element.kind.isToggle -> ScreenAction.SetChecked(element.id, element.isChecked != true)
            else -> ScreenAction.Click(element.id)
        }
        run(action, describe(element))
    }

    override fun onScrollUp() = run(ScreenAction.Scroll(ScrollDirection.UP), "Scrolled up")

    override fun onScrollDown() = run(ScreenAction.Scroll(ScrollDirection.DOWN), "Scrolled down")

    override fun onBack() = run(ScreenAction.Back, "Went back")

    override fun onClosePanel() = local.update { it.copy(panelOpen = false) }

    override fun onHelpVideo(url: String) {
        _effects.tryEmit(OverlayEffect.OpenUrl(url))
    }

    private fun run(action: ScreenAction, successText: String) {
        scope.launch {
            local.update { it.copy(flashMode = BubbleMode.ACTING) }
            when (val result = screen.perform(action)) {
                ActionResult.Success -> flashCaption(successText, BubbleMode.IDLE)
                is ActionResult.Failure -> flashCaption(result.reason, BubbleMode.ERROR)
            }
            if (local.value.panelOpen) {
                delay(SCREEN_SETTLE_MS)
                val snapshot = screen.capture()
                local.update { it.copy(panelElements = snapshot?.elements.orEmpty()) }
            }
        }
    }

    private fun describe(element: ScreenElement): String = when {
        element.kind == ElementKind.TEXT_FIELD -> "Selected ${element.label}"
        element.kind.isToggle -> "Toggled ${element.label}"
        else -> "Pressed ${element.label}"
    }

    private fun flashCaption(text: String, mode: BubbleMode) {
        captionJob?.cancel()
        local.update { it.copy(flashCaption = text, flashMode = mode) }
        captionJob = scope.launch {
            delay(CAPTION_MS)
            local.update { it.copy(flashCaption = null, flashMode = null) }
        }
    }

    private fun toUi(e: EngineState, l: LocalState): OverlayUiState {
        val engineMode = when (e.status) {
            EngineStatus.LISTENING -> BubbleMode.LISTENING
            EngineStatus.SPEAKING -> BubbleMode.SPEAKING
            EngineStatus.THINKING, EngineStatus.STARTING -> BubbleMode.THINKING
            EngineStatus.ACTING -> BubbleMode.ACTING
            EngineStatus.ERROR -> BubbleMode.ERROR
            EngineStatus.IDLE, EngineStatus.PAUSED, EngineStatus.FINISHED -> BubbleMode.IDLE
        }
        return OverlayUiState(
            visible = l.visible,
            mode = if (e.active) engineMode else l.flashMode ?: if (e.status == EngineStatus.ERROR) BubbleMode.ERROR else BubbleMode.IDLE,
            sessionActive = e.active,
            caption = if (e.active) e.caption else l.flashCaption,
            heard = if (e.active) e.heard else null,
            progress = if (e.active) e.progress else null,
            helpVideoUrl = if (e.active) e.helpVideoUrl else null,
            panelOpen = l.panelOpen,
            panelElements = l.panelElements,
            micLevel = e.micLevel,
        )
    }

    private companion object {
        const val SCREEN_SETTLE_MS = 600L
        const val CAPTION_MS = 2500L
        const val MIC_PERMISSION_TEXT = "Allow microphone access in the VoiceControl app"
    }
}
