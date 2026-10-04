package com.voicecontrol.core.engine

import com.voicecontrol.core.model.ElementKind
import com.voicecontrol.core.model.FlowDefinition
import com.voicecontrol.core.model.FlowStep
import com.voicecontrol.core.model.StepAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class FlowItemSwapTest {
    private val flow = FlowDefinition(
        id = "f", version = 1, appPackage = "com.zepto", name = "Zepto pe maggi order karo", screenSignature = "s",
        steps = listOf(
            FlowStep("a", 0, "vid:search", "Search", ElementKind.BUTTON, action = StepAction.CLICK, skip = true),
            FlowStep("b", 1, "vid:q", "Search", ElementKind.TEXT_FIELD, action = StepAction.FILL, defaultValue = "maggi", skip = true),
        ),
        updatedAtMillis = 0,
    )

    @Test
    fun `a different item is typed instead`() {
        val adapted = FlowItemSwap.adapt(flow, "Zepto pe maggi order karo", "zepto pe doodh order karo")!!
        assertEquals("doodh", adapted.steps.single { it.id == "b" }.defaultValue)
        assertEquals("amul taaza doodh", FlowItemSwap.adapt(flow, "Zepto pe maggi order karo", "Zepto pe amul taaza doodh order karo")!!.steps[1].defaultValue)
    }

    @Test
    fun `the same words keep the flow, other changes aren't run`() {
        assertSame(flow, FlowItemSwap.adapt(flow, "Zepto pe maggi order karo", "please zepto pe maggi order karo"))
        // A misheard word is still the same request.
        assertSame(flow, FlowItemSwap.adapt(flow, "Zepto pe maggi order karo", "zepto pe maggie order karo"))
        // A different app: the flow can't do that.
        assertNull(FlowItemSwap.adapt(flow, "Zepto pe maggi order karo", "Blinkit pe maggi order karo"))
    }
}
