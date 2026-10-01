package com.voicecontrol.feature.assistant

import com.voicecontrol.core.engine.port.ScreenGateway
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** One-off requests from the controller that need an Android context (handled by the overlay manager). */
sealed interface OverlayEffect {
    data class OpenUrl(val url: String) : OverlayEffect
}

/**
 * Owns the overlay's state and turns overlay gestures into screen actions.
 * The touch panel (long-press) lets users click/focus any detected element manually.
 */
@Singleton
class AssistantController @Inject constructor(
    private val screen: ScreenGateway,
) : OverlayActions {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(OverlayUiState())
    val state: StateFlow<OverlayUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<OverlayEffect>(extraBufferCapacity = 8)
    val effects: SharedFlow<OverlayEffect> = _effects.asSharedFlow()

    private var captionJob: Job? = null

    override fun onMicTap() = togglePanel()

    override fun onMicLongPress() = togglePanel()

    private fun togglePanel() {
        if (_state.value.panelOpen) {
            onClosePanel()
            return
        }
        scope.launch {
            val snapshot = screen.capture()
            _state.update { it.copy(panelOpen = true, panelElements = snapshot?.elements.orEmpty()) }
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

    override fun onClosePanel() = _state.update { it.copy(panelOpen = false) }

    override fun onHelpVideo(url: String) {
        _effects.tryEmit(OverlayEffect.OpenUrl(url))
    }

    private fun run(action: ScreenAction, successText: String) {
        scope.launch {
            setMode(BubbleMode.ACTING)
            val result = screen.perform(action)
            when (result) {
                ActionResult.Success -> flashCaption(successText, BubbleMode.IDLE)
                is ActionResult.Failure -> flashCaption(result.reason, BubbleMode.ERROR)
            }
            // Element list may have changed (e.g. after navigation).
            if (_state.value.panelOpen) {
                delay(SCREEN_SETTLE_MS)
                val snapshot = screen.capture()
                _state.update { it.copy(panelElements = snapshot?.elements.orEmpty()) }
            }
        }
    }

    private fun describe(element: ScreenElement): String = when {
        element.kind == ElementKind.TEXT_FIELD -> "Selected ${element.label}"
        element.kind.isToggle -> "Toggled ${element.label}"
        else -> "Pressed ${element.label}"
    }

    private fun setMode(mode: BubbleMode) = _state.update { it.copy(mode = mode) }

    private fun flashCaption(text: String, mode: BubbleMode) {
        captionJob?.cancel()
        _state.update { it.copy(caption = text, mode = mode) }
        captionJob = scope.launch {
            delay(CAPTION_MS)
            _state.update { it.copy(caption = null, mode = BubbleMode.IDLE) }
        }
    }

    private companion object {
        const val SCREEN_SETTLE_MS = 600L
        const val CAPTION_MS = 2500L
    }
}
