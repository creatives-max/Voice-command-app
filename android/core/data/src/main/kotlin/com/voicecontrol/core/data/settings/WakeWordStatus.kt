package com.voicecontrol.core.data.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** What the wake phrase listener is doing, shown in Settings so people can see why it doesn't react. */
enum class WakeState {
    OFF,
    LISTENING,
    SCREEN_OFF,
    SESSION,
    PAUSED,
    NO_MIC_PERMISSION,
    /** Android blocked the microphone in the background: opening VoiceControl once fixes it. */
    MIC_BLOCKED,
    RECOGNIZER_ERROR,
    ACCESSIBILITY_OFF,
}

@Singleton
class WakeWordStatus @Inject constructor() {
    private val _state = MutableStateFlow(WakeState.ACCESSIBILITY_OFF)
    val state: StateFlow<WakeState> = _state.asStateFlow()

    fun set(state: WakeState) {
        _state.value = state
    }
}
