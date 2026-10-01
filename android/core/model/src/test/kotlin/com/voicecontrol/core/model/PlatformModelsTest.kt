package com.voicecontrol.core.model

import com.voicecontrol.core.model.diagnostics.CrashRecords
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlatformModelsTest {
    private val flow = FlowDefinition(
        id = "local-1",
        appPackage = "com.shop",
        name = "Checkout",
        screenSignature = "sig",
        steps = listOf(
            FlowStep("a", 0, "vid:name", "Name", ElementKind.TEXT_FIELD, FieldType.NAME),
            FlowStep("b", 1, "vid:otp", "OTP", ElementKind.TEXT_FIELD, FieldType.OTP),
            FlowStep("loop", 2, "", "Items", ElementKind.BUTTON, action = StepAction.REPEAT, repeat = RepeatSpec(stepIds = listOf("a"))),
            FlowStep("c", 3, "vid:pay", "Pay", ElementKind.BUTTON, action = StepAction.CLICK),
        ),
    )

    @Test
    fun `edits renumber steps and protect sensitive fields`() {
        val moved = FlowEditing.move(flow, "c", -10)
        assertEquals(listOf("c", "a", "b", "loop"), moved.orderedSteps.map { it.id })
        assertEquals(listOf(0, 1, 2, 3), moved.orderedSteps.map { it.order })
        assertEquals(flow, FlowEditing.move(flow, "a", -1))

        val withDefault = FlowEditing.setDefault(flow, "a", "  Asha ")
        assertEquals("Asha", withDefault.steps.first { it.id == "a" }.defaultValue)
        assertNull(FlowEditing.setDefault(withDefault, "a", " ").steps.first { it.id == "a" }.defaultValue)
        assertFailsWith<IllegalArgumentException> { FlowEditing.setDefault(flow, "b", "123456") }
        assertFailsWith<IllegalArgumentException> { FlowEditing.setDefault(flow, "c", "x") }

        val removed = FlowEditing.remove(flow, "a")
        assertEquals(emptyList(), removed.steps.first { it.id == "loop" }.repeat!!.stepIds)
        assertTrue(FlowEditing.setSkip(flow, "c", true).steps.first { it.id == "c" }.skip)
        assertEquals("Ask nicely", FlowEditing.setQuestion(flow, "a", " Ask nicely ").steps.first { it.id == "a" }.question)
        assertFailsWith<IllegalArgumentException> { FlowEditing.rename(flow, " ") }
    }

    @Test
    fun `crash records remove personal data and keep causes`() {
        val error = IllegalStateException("Could not fill 9876543210 for priya@example.com", RuntimeException("OTP 482913 rejected"))
        val record = CrashRecords.from(error, "main", "1.2.0", 34, "Pixel", 1_000L, "id-1")
        assertEquals("java.lang.IllegalStateException", record.exception)
        assertEquals("Could not fill <digits> for <email>", record.message)
        assertTrue(record.stacktrace.contains("Caused by: java.lang.RuntimeException: OTP <digits> rejected"))
        assertTrue(record.stacktrace.contains("    at com.voicecontrol.core.model.PlatformModelsTest"))
        assertFalse(record.stacktrace.contains("priya"))
    }
}
