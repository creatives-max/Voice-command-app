package com.voicecontrol.feature.assistant.teach

import com.voicecontrol.core.data.teach.TaughtFlows
import com.voicecontrol.core.engine.RecordingSession
import com.voicecontrol.core.engine.RecordingState
import com.voicecontrol.core.engine.port.InteractionSource
import com.voicecontrol.core.engine.port.LaunchResult
import com.voicecontrol.core.engine.port.ScreenGateway
import com.voicecontrol.core.engine.port.TeachLauncher
import com.voicecontrol.feature.assistant.AssistantController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** "Teach by doing" from the overlay or the app: record, then hand the recording over for review. */
@Singleton
class TeachController @Inject constructor(
    screen: ScreenGateway,
    interactions: InteractionSource,
    private val assistant: AssistantController,
    private val taught: TaughtFlows,
) : TeachLauncher {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val session = RecordingSession(screen, interactions, scope)
    private val serviceOn = screen.isAvailable

    val state: StateFlow<RecordingState> = session.state

    private val _finished = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    /** Emits after stopping: true when something was recorded (the review screen should open). */
    val finished: SharedFlow<Boolean> = _finished.asSharedFlow()

    override fun startTeaching(): LaunchResult = when {
        !serviceOn.value -> LaunchResult.SERVICE_OFF
        assistant.sessionActive || session.isActive -> LaunchResult.BUSY
        session.start() -> LaunchResult.STARTED
        else -> LaunchResult.BUSY
    }

    fun stop() {
        scope.launch {
            val recording = session.stop()
            taught.setPending(recording)
            _finished.emit(!recording.isEmpty)
        }
    }
}
