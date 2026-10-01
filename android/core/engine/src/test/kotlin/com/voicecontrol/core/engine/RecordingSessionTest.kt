package com.voicecontrol.core.engine

import com.voicecontrol.core.engine.port.InteractionKind
import com.voicecontrol.core.engine.port.InteractionSource
import com.voicecontrol.core.engine.port.ScreenGateway
import com.voicecontrol.core.engine.port.UserInteraction
import com.voicecontrol.core.model.ActionResult
import com.voicecontrol.core.model.ElementKind
import com.voicecontrol.core.model.FieldType
import com.voicecontrol.core.model.ScreenAction
import com.voicecontrol.core.model.ScreenElement
import com.voicecontrol.core.model.ScreenSnapshot
import com.voicecontrol.core.model.Screenshot
import com.voicecontrol.core.model.StepAction
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecordingSessionTest {
    private val name = ScreenElement("vid:name", ElementKind.TEXT_FIELD, "Name", FieldType.NAME)
    private val go = ScreenElement("vid:go", ElementKind.BUTTON, "Continue")
    private val city = ScreenElement("vid:city", ElementKind.TEXT_FIELD, "City")

    private class Gateway(start: ScreenSnapshot) : ScreenGateway {
        var current = start
        val changes = MutableStateFlow<ScreenSnapshot?>(null)
        var available = MutableStateFlow(true)
        override val isAvailable: StateFlow<Boolean> get() = available
        override val screenChanges = changes.filterNotNull()
        override suspend fun capture() = current
        override suspend fun perform(action: ScreenAction) = ActionResult.Success
        override suspend fun screenshot(): Screenshot? = null
    }

    private class Touches : InteractionSource {
        val events = MutableSharedFlow<UserInteraction>(extraBufferCapacity = 16)
        override val interactions = events
    }

    @Test
    fun `records typing and presses across screens`() = runTest {
        val page1 = ScreenSnapshot("com.form", title = "Details", elements = listOf(name, go), signature = "p1")
        val gateway = Gateway(page1)
        val touches = Touches()
        val session = RecordingSession(gateway, touches, backgroundScope, settleMillis = 100)
        assertTrue(session.start())
        assertFalse(session.start())
        runCurrent()

        gateway.current = page1.copy(elements = listOf(name.copy(value = "Ravi"), go))
        touches.events.emit(UserInteraction(InteractionKind.TYPED, "vid:name"))
        runCurrent()
        advanceTimeBy(150)
        touches.events.emit(UserInteraction(InteractionKind.PRESSED, "vid:go"))
        runCurrent()
        val page2 = ScreenSnapshot("com.form", elements = listOf(city.copy(value = "Agra")), signature = "p2")
        gateway.current = page2
        gateway.changes.value = page2
        runCurrent()
        touches.events.emit(UserInteraction(InteractionKind.TYPED, "vid:city"))
        runCurrent()
        assertEquals(3, session.state.value.actions)

        val recording = session.stop()
        assertFalse(session.isActive)
        assertEquals(2, recording.screens.size)
        assertEquals("Ravi", recording.screens[0].actions[0].value)
        assertEquals(StepAction.CLICK, recording.screens[0].actions[1].action)
        assertEquals("Agra", recording.screens[1].actions.single().value)
    }

    @Test
    fun `does not start without the accessibility service`() = runTest {
        val gateway = Gateway(ScreenSnapshot("x", elements = emptyList()))
        gateway.available.value = false
        assertFalse(RecordingSession(gateway, Touches(), backgroundScope).start())
    }
}
