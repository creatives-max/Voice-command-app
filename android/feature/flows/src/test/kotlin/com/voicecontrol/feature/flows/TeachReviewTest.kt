package com.voicecontrol.feature.flows

import com.voicecontrol.core.engine.FlowRecorder
import com.voicecontrol.core.engine.RecordedEvent
import com.voicecontrol.core.engine.port.LaunchResult
import com.voicecontrol.core.model.ElementKind
import com.voicecontrol.core.model.FieldType
import com.voicecontrol.core.model.ScreenElement
import com.voicecontrol.core.model.ScreenSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TeachReviewTest {
    @Test
    fun reviewRowsDescribeEachStepAndScreenChange() {
        val name = ScreenElement("vid:name", ElementKind.TEXT_FIELD, "Name", FieldType.NAME, value = "Asha")
        val pin = ScreenElement("vid:pin", ElementKind.TEXT_FIELD, "PIN", FieldType.PIN, isSensitive = true)
        val go = ScreenElement("vid:go", ElementKind.BUTTON, "Go")
        val r = FlowRecorder()
        r.onEvent(RecordedEvent.Screen(ScreenSnapshot("com.a", elements = listOf(name, pin, go), signature = "1")))
        r.onEvent(RecordedEvent.Typed("vid:name"))
        r.onEvent(RecordedEvent.Typed("vid:pin"))
        r.onEvent(RecordedEvent.Pressed("vid:go"))
        r.onEvent(RecordedEvent.Screen(ScreenSnapshot("com.b", elements = listOf(go), signature = "2")))
        r.onEvent(RecordedEvent.Pressed("vid:go"))

        val rows = reviewSteps(r.recording())
        assertEquals(listOf("Fill “Name”", "Fill “PIN”", "Press “Go”", "Switch to com.b", "Press “Go”"), rows.map { it.title })
        assertEquals("Asha", rows[0].value)
        assertNull(rows[1].value)
        assertEquals("Typed by you each time", rows[1].detail)
    }

    @Test
    fun teachMessages() {
        assertNull(teachMessage(LaunchResult.STARTED))
        assertEquals("Turn on the VoiceControl accessibility service first", teachMessage(LaunchResult.SERVICE_OFF))
    }
}
