package com.voicecontrol.feature.flows

import com.voicecontrol.core.model.ElementKind
import com.voicecontrol.core.model.FieldType
import com.voicecontrol.core.model.FlowDefinition
import com.voicecontrol.core.model.FlowStep
import com.voicecontrol.core.model.ProfileKey
import com.voicecontrol.core.model.StepAction
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DryRunSessionTest {
    private val flow = FlowDefinition(
        id = "local-1",
        appPackage = "com.shop",
        name = "Sign up",
        screenSignature = "sig",
        steps = listOf(
            FlowStep("n", 0, "vid:n", "Name", ElementKind.TEXT_FIELD, FieldType.NAME, profileKey = ProfileKey.FULL_NAME),
            FlowStep("t", 1, "vid:t", "Accept terms", ElementKind.CHECKBOX, action = StepAction.TOGGLE),
            FlowStep("go", 2, "vid:go", "Sign up", ElementKind.BUTTON, action = StepAction.CLICK),
        ),
    )
    private val start = DryRunSession.start(flow, mapOf("profile.name" to "Asha Rao"), LocalDate.of(2026, 10, 1))

    @Test
    fun `answers move the run forward and undo goes back`() {
        assertEquals("Please say Name. Say yes to use Asha Rao.", start.result.pending?.question)
        assertEquals(listOf("Yes", "No", "Skip"), start.suggestions)

        val named = start.answer("Yes")
        assertEquals("Asha Rao", named.result.values["n"])
        assertEquals(listOf("Yes", "No"), named.suggestions)

        val done = named.answer("haan").answer("yes")
        assertTrue(done.result.finished)
        assertNull(done.result.pending)
        assertTrue(done.suggestions.isEmpty())
        assertEquals(mapOf("n" to "Asha Rao", "t" to "yes"), done.result.values)

        // No question pending: further answers are ignored.
        assertSame(done, done.answer("more"))
        val back = done.undo()
        assertFalse(back.result.finished)
        assertEquals("go", back.result.pending?.stepId)
        assertEquals(start.result, done.restart().result)
    }

    @Test
    fun `blank answers are ignored`() {
        assertSame(start, start.answer("   "))
        assertSame(start, start.undo())
    }
}
