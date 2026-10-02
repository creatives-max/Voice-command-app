package com.voicecontrol.core.engine

import com.voicecontrol.core.engine.port.InteractionKind
import com.voicecontrol.core.engine.port.InteractionSource
import com.voicecontrol.core.engine.port.ScreenGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class RecordingState(val active: Boolean = false, val actions: Int = 0, val screens: Int = 0)

/**
 * "Teach by doing": while active, everything the user types, toggles and presses in other apps is
 * recorded together with the screens it happened on. After each action the screen is read again
 * (once things settle) so typed text and the next screen are captured.
 */
class RecordingSession(
    private val screen: ScreenGateway,
    private val source: InteractionSource,
    private val scope: CoroutineScope,
    private val settleMillis: Long = 400,
    /** Home-screen (launcher) apps: tapping an app there becomes "open that app". */
    homePackages: () -> Set<String> = { emptySet() },
) {
    private val recorder = FlowRecorder(homePackages)
    private val lock = Mutex()
    private var job: Job? = null
    private val refresh = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val _state = MutableStateFlow(RecordingState())
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    val isActive: Boolean get() = _state.value.active

    /** Starts recording; false when the accessibility service is off or a recording is already running. */
    fun start(): Boolean {
        if (isActive || !screen.isAvailable.value) return false
        recorder.clear()
        _state.value = RecordingState(active = true)
        job = scope.launch {
            screen.capture()?.let { handle(RecordedEvent.Screen(it)) }
            merge(
                screen.screenChanges.map { RecordedEvent.Screen(it) },
                source.interactions.map { i ->
                    when (i.kind) {
                        InteractionKind.TYPED -> RecordedEvent.Typed(i.elementId, i.screen)
                        InteractionKind.PRESSED -> RecordedEvent.Pressed(i.elementId, i.screen)
                    }
                },
                refresh.debounce(settleMillis).mapNotNull { screen.capture()?.let { RecordedEvent.Screen(it) } },
            ).collect { event ->
                handle(event)
                if (event !is RecordedEvent.Screen) refresh.tryEmit(Unit)
            }
        }
        return true
    }

    /** Stops and returns what was recorded (the current screen is read one last time). */
    suspend fun stop(): Recording {
        job?.cancelAndJoin()
        job = null
        screen.capture()?.let { handle(RecordedEvent.Screen(it)) }
        val result = lock.withLock { recorder.recording() }
        _state.value = RecordingState()
        return result
    }

    private suspend fun handle(event: RecordedEvent) = lock.withLock {
        recorder.onEvent(event)
        val r = recorder.recording()
        _state.value = RecordingState(active = true, actions = r.actionCount, screens = r.screens.size)
    }
}
