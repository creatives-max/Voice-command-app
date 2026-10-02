package com.voicecontrol.core.engine

import com.voicecontrol.core.engine.port.ListenRequest
import com.voicecontrol.core.engine.port.ListenResult
import com.voicecontrol.core.engine.port.ScreenGateway
import com.voicecontrol.core.engine.port.SpeechToText
import com.voicecontrol.core.engine.port.TextToSpeech
import com.voicecontrol.core.model.ActionResult
import com.voicecontrol.core.model.ScreenAction
import com.voicecontrol.core.model.ScreenSnapshot
import com.voicecontrol.core.model.Screenshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/** A screen whose fields actually change when SetText runs, and that can switch screens on click. */
class FakeScreen(var snapshot: ScreenSnapshot?) : ScreenGateway {
    val actions = mutableListOf<ScreenAction>()
    /** Screen to show after clicking the given element id. */
    val onClick = mutableMapOf<String, ScreenSnapshot>()
    /** Screen to show after launching the given package. */
    val onLaunch = mutableMapOf<String, ScreenSnapshot>()
    /** Screen to show after pressing Enter in the given field. */
    val onEnter = mutableMapOf<String, ScreenSnapshot>()

    override val isAvailable: StateFlow<Boolean> = MutableStateFlow(true)
    override val screenChanges: Flow<ScreenSnapshot> = emptyFlow()
    override suspend fun capture(): ScreenSnapshot? = snapshot
    var shot: Screenshot? = null
    override suspend fun screenshot(): Screenshot? = shot

    override suspend fun perform(action: ScreenAction): ActionResult {
        actions += action
        val snap = snapshot ?: return ActionResult.Failure("no screen")
        when (action) {
            is ScreenAction.SetText -> snapshot = snap.copy(elements = snap.elements.map { if (it.id == action.elementId) it.copy(value = action.text) else it })
            is ScreenAction.Click -> onClick[action.elementId]?.let { snapshot = it }
            is ScreenAction.SetChecked -> snapshot = snap.copy(elements = snap.elements.map { if (it.id == action.elementId) it.copy(isChecked = action.checked) else it })
            is ScreenAction.LaunchApp -> snapshot = onLaunch[action.packageName] ?: return ActionResult.Failure("not installed")
            is ScreenAction.PressEnter -> snapshot = onEnter[action.elementId] ?: return ActionResult.Failure("no enter")
            else -> Unit
        }
        return ActionResult.Success
    }

    fun valueOf(id: String) = snapshot?.element(id)?.value
}

class ScriptedStt(vararg answers: String?) : SpeechToText {
    private val queue = ArrayDeque(answers.toList())
    val requests = mutableListOf<ListenRequest>()

    override suspend fun listen(request: ListenRequest, onPartial: (String) -> Unit, onLevel: (Float) -> Unit): ListenResult {
        requests += request
        if (queue.isEmpty()) return ListenResult.NoMatch
        val next = queue.removeFirst() ?: return ListenResult.NoMatch
        onPartial(next)
        return ListenResult.Heard(next)
    }

    override fun cancel() = Unit
}

class RecordingTts : TextToSpeech {
    val spoken = mutableListOf<String>()
    override suspend fun speak(text: String, languageTag: String, rate: Float): Boolean {
        spoken += text
        return true
    }
    override fun stop() = Unit
}
