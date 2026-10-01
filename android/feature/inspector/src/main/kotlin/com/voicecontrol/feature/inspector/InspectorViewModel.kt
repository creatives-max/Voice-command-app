package com.voicecontrol.feature.inspector

import androidx.lifecycle.viewModelScope
import com.voicecontrol.core.accessibility.AccessibilityBridge
import com.voicecontrol.core.ui.mvi.MviViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

@HiltViewModel
class InspectorViewModel @Inject constructor(
    private val bridge: AccessibilityBridge,
) : MviViewModel<InspectorState, InspectorIntent, InspectorEffect>(InspectorState()) {

    init {
        combine(bridge.isConnected, bridge.currentSnapshot) { connected, snapshot -> connected to snapshot }
            .onEach { (connected, snapshot) -> setState { copy(serviceConnected = connected, snapshot = snapshot) } }
            .launchIn(viewModelScope)
    }

    override suspend fun handleIntent(intent: InspectorIntent) {
        when (intent) {
            InspectorIntent.Refresh -> {
                if (!bridge.isConnected.value) {
                    sendEffect(InspectorEffect.Message("Enable the VoiceControl accessibility service first"))
                    return
                }
                setState { copy(refreshing = true) }
                val snapshot = bridge.captureScreen()
                setState { copy(refreshing = false, snapshot = snapshot ?: this.snapshot) }
            }
            is InspectorIntent.ToggleButtons -> setState { copy(showButtons = intent.show) }
        }
    }
}
