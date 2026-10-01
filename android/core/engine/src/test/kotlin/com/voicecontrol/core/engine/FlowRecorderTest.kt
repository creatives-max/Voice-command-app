package com.voicecontrol.core.engine

import com.voicecontrol.core.model.ElementKind
import com.voicecontrol.core.model.FieldType
import com.voicecontrol.core.model.ScreenElement
import com.voicecontrol.core.model.ScreenSnapshot
import com.voicecontrol.core.model.StepAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlowRecorderTest {
    private val name = ScreenElement("vid:name", ElementKind.TEXT_FIELD, "Name", FieldType.NAME)
    private val pin = ScreenElement("vid:pin", ElementKind.TEXT_FIELD, "PIN", FieldType.PIN, isSensitive = true)
    private val terms = ScreenElement("vid:terms", ElementKind.CHECKBOX, "I agree", isChecked = false)
    private val next = ScreenElement("vid:next", ElementKind.BUTTON, "Next")
    private val city = ScreenElement("vid:city", ElementKind.TEXT_FIELD, "City", FieldType.TEXT)
    private val pay = ScreenElement("vid:pay", ElementKind.BUTTON, "Pay")

    private fun screen(pkg: String, sig: String, vararg e: ScreenElement, title: String? = null) =
        ScreenSnapshot(pkg, title = title, elements = e.toList(), signature = sig)

    @Test
    fun `records screens in order with boundaries and opt-in defaults`() {
        val r = FlowRecorder()
        r.onEvent(RecordedEvent.Screen(screen("com.shop", "s1", name, pin, terms, next, title = "Sign up")))
        r.onEvent(RecordedEvent.Pressed("vid:name")) // focusing a field is ignored
        r.onEvent(RecordedEvent.Typed("vid:name"))
        r.onEvent(RecordedEvent.Screen(screen("com.shop", "s1", name.copy(value = "Asha Rao"), pin.copy(value = null), terms, next, title = "Sign up")))
        r.onEvent(RecordedEvent.Typed("vid:pin"))
        r.onEvent(RecordedEvent.Pressed("vid:terms")) // a press on a checkbox is a toggle
        r.onEvent(RecordedEvent.Pressed("vid:next"))
        r.onEvent(RecordedEvent.Screen(screen("com.shop", "loading"))) // nothing done here: dropped
        r.onEvent(RecordedEvent.Screen(screen("com.shop", "s2", city, title = "Address")))
        r.onEvent(RecordedEvent.Typed("vid:city"))
        r.onEvent(RecordedEvent.Screen(screen("com.shop", "s2", city.copy(value = "Pune"), title = "Address")))
        r.onEvent(RecordedEvent.Screen(screen("com.bank", "b1", pay)))
        r.onEvent(RecordedEvent.Pressed("vid:pay"))
        r.onEvent(RecordedEvent.Pressed("vid:missing")) // not on screen: ignored

        val recording = r.recording()
        assertEquals(3, recording.screens.size)
        assertEquals("Asha Rao", recording.screens[0].actions.first().value)
        assertNull(recording.screens[0].actions[1].value)

        val flow = RecordingToFlow.build(recording, "local-1", 5L, keepValues = setOf("vid:name", "vid:pin", "vid:city"))
        assertEquals("com.shop", flow.appPackage)
        assertEquals("s1", flow.screenSignature)
        assertEquals("Shop · Sign up", flow.name)
        assertEquals(
            listOf(StepAction.FILL, StepAction.FILL, StepAction.TOGGLE, StepAction.CLICK, StepAction.NEXT_SCREEN, StepAction.FILL, StepAction.OPEN_APP, StepAction.CLICK),
            flow.orderedSteps.map { it.action },
        )
        val steps = flow.orderedSteps
        assertEquals("Asha Rao", steps[0].defaultValue)
        assertNull(steps[1].defaultValue) // PIN never keeps a value
        assertEquals("Address", steps[4].label)
        assertEquals("com.bank", steps[6].appPackage)
        assertEquals((0 until steps.size).toList(), steps.map { it.order })
        assertTrue(steps.map { it.id }.toSet().size == steps.size)
        assertNull(RecordingToFlow.build(recording, "local-2", 5L).orderedSteps[0].defaultValue)
    }

    @Test
    fun `pressing a button again moves it last and empty recordings are refused`() {
        val r = FlowRecorder()
        r.onEvent(RecordedEvent.Screen(screen("com.a", "s", name, next)))
        r.onEvent(RecordedEvent.Pressed("vid:next"))
        r.onEvent(RecordedEvent.Typed("vid:name"))
        r.onEvent(RecordedEvent.Pressed("vid:next"))
        assertEquals(listOf("vid:name", "vid:next"), r.recording().screens.single().actions.map { it.element.id })
        r.clear()
        assertTrue(r.recording().isEmpty)
        assertFailsWith<IllegalArgumentException> { RecordingToFlow.build(r.recording(), "x", 0) }
    }
}
