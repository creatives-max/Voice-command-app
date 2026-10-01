package com.voicecontrol.application.flow

import com.voicecontrol.domain.ai.ElementKind
import com.voicecontrol.domain.ai.FieldType
import com.voicecontrol.domain.common.DomainException
import com.voicecontrol.domain.flow.FlowStep
import com.voicecontrol.domain.flow.RepeatSpec
import com.voicecontrol.domain.flow.StepAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlowLogicValidationTest {
    private fun field(id: String, order: Int) = FlowStep(id, order, "vid:$id", "Field $id", ElementKind.TEXT_FIELD, FieldType.TEXT)
    private fun logic(id: String, order: Int, action: StepAction) = FlowStep(id, order, "", "", ElementKind.BUTTON, action = action)

    private fun invalid(vararg steps: FlowStep): String =
        assertFailsWith<DomainException.Validation> { FlowService.normalizeSteps(steps.toList()) }.message!!

    @Test
    fun `valid logic flow is accepted and trimmed`() {
        val steps = FlowService.normalizeSteps(
            listOf(
                field("a", 0).copy(variable = " kids ", question = "How many kids, {profile.first_name}?"),
                field("b", 1).copy(condition = "kids > 0", elseValue = "'none'"),
                logic("v", 2, StepAction.SET_VARIABLE).copy(variable = "total", valueExpression = "kids + 1"),
                logic("r", 3, StepAction.REPEAT).copy(repeat = RepeatSpec(listOf("b"), countExpression = "kids")),
                logic("n", 4, StepAction.NEXT_SCREEN).copy(waitSeconds = 30),
                logic("o", 5, StepAction.OPEN_APP).copy(appPackage = "com.pay.app"),
                field("c", 6).copy(action = StepAction.READ, variable = "ref"),
            ),
        )
        assertEquals("kids", steps[0].variable)
        assertEquals(7, steps.size)
        assertNull(steps[0].repeat)
    }

    @Test
    fun `broken expressions and bad variables are rejected with the step number`() {
        assertTrue(invalid(field("a", 0).copy(condition = "kids >")).startsWith("Step 1: condition"))
        assertTrue(invalid(field("a", 0), field("b", 1).copy(valueExpression = "nosuchfn(1)")).startsWith("Step 2: computed value"))
        assertTrue(invalid(field("a", 0).copy(question = "Hi {first(}")).contains("placeholder"))
        assertTrue(invalid(field("a", 0).copy(variable = "my var")).contains("variable names"))
        assertTrue(invalid(field("a", 0).copy(variable = "index")).contains("reserved"))
        assertTrue(invalid(field("a", 0).copy(elseValue = "'x'")).contains("needs a condition"))
    }

    @Test
    fun `logic steps need their settings`() {
        assertTrue(invalid(logic("v", 0, StepAction.SET_VARIABLE).copy(variable = "x")).contains("set-variable"))
        assertTrue(invalid(logic("o", 0, StepAction.OPEN_APP)).contains("app package"))
        assertTrue(invalid(logic("o", 0, StepAction.OPEN_APP).copy(appPackage = "not a package")).contains("package"))
        assertTrue(invalid(logic("r", 0, StepAction.REPEAT)).contains("repeat steps need"))
        assertTrue(invalid(field("a", 0).copy(elementId = "")).contains("elementId"))
        assertTrue(invalid(logic("n", 0, StepAction.NEXT_SCREEN).copy(waitSeconds = 0)).contains("1-120"))
    }

    @Test
    fun `repeat groups must reference real steps on the same screen`() {
        assertTrue(invalid(logic("r", 0, StepAction.REPEAT).copy(repeat = RepeatSpec(listOf("ghost")))).contains("does not exist"))
        assertTrue(
            invalid(field("a", 0), logic("n", 1, StepAction.NEXT_SCREEN), logic("r", 2, StepAction.REPEAT).copy(repeat = RepeatSpec(listOf("a"))))
                .contains("same screen"),
        )
        assertTrue(
            invalid(
                field("a", 0),
                logic("r1", 1, StepAction.REPEAT).copy(repeat = RepeatSpec(listOf("a"))),
                logic("r2", 2, StepAction.REPEAT).copy(repeat = RepeatSpec(listOf("a"))),
            ).contains("only one loop"),
        )
    }

    @Test
    fun `sensitive fields are never filled automatically`() {
        val pin = FlowStep("p", 0, "vid:pin", "PIN", ElementKind.TEXT_FIELD, FieldType.PIN, valueExpression = "'1234'")
        assertTrue(invalid(pin).contains("cannot be filled automatically"))
    }
}
