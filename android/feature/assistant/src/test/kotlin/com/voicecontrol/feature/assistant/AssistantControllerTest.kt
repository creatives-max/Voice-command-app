package com.voicecontrol.feature.assistant

import com.voicecontrol.core.model.ActionResult
import com.voicecontrol.core.model.ElementKind
import com.voicecontrol.core.model.ScreenAction
import com.voicecontrol.core.model.ScreenElement
import com.voicecontrol.core.model.ScreenSnapshot
import com.voicecontrol.feature.assistant.overlay.BubbleMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AssistantControllerTest {
    private val dispatcher = StandardTestDispatcher()
    private val name = ScreenElement("vid:name", ElementKind.TEXT_FIELD, "Name")
    private val terms = ScreenElement("vid:terms", ElementKind.CHECKBOX, "Accept", isChecked = false)
    private val submit = ScreenElement("vid:submit", ElementKind.BUTTON, "Submit")
    private val gateway = FakeScreenGateway(ScreenSnapshot("com.app", elements = listOf(name, terms, submit)))

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `panel lists current screen elements`() = runTest(dispatcher) {
        val controller = AssistantController(gateway)
        controller.onMicLongPress()
        runCurrent()
        assertTrue(controller.state.value.panelOpen)
        assertEquals(3, controller.state.value.panelElements.size)
    }

    @Test
    fun `element taps map to the right action`() = runTest(dispatcher) {
        val controller = AssistantController(gateway)
        controller.onElementTap(name)
        controller.onElementTap(terms)
        controller.onElementTap(submit)
        runCurrent()
        assertEquals(
            listOf(ScreenAction.Focus("vid:name"), ScreenAction.SetChecked("vid:terms", true), ScreenAction.Click("vid:submit")),
            gateway.performed,
        )
        assertEquals("Pressed Submit", controller.state.value.caption)
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(null, controller.state.value.caption)
    }

    @Test
    fun `failures are shown as errors`() = runTest(dispatcher) {
        gateway.nextResult = ActionResult.Failure("Element vid:x is not on screen")
        val controller = AssistantController(gateway)
        controller.onBack()
        runCurrent()
        assertEquals(BubbleMode.ERROR, controller.state.value.mode)
    }
}
