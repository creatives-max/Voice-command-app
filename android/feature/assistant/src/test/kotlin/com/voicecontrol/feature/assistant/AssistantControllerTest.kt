package com.voicecontrol.feature.assistant

import com.voicecontrol.core.engine.AssistantEngine
import com.voicecontrol.core.engine.LocalInterpreter
import com.voicecontrol.core.engine.port.ListenRequest
import com.voicecontrol.core.engine.port.ListenResult
import com.voicecontrol.core.engine.port.SessionConfig
import com.voicecontrol.core.engine.port.SpeechToText
import com.voicecontrol.core.engine.port.TextToSpeech
import com.voicecontrol.core.model.ActionResult
import com.voicecontrol.core.model.ElementKind
import com.voicecontrol.core.model.ScreenAction
import com.voicecontrol.core.model.ScreenElement
import com.voicecontrol.core.model.ScreenSnapshot
import com.voicecontrol.feature.assistant.overlay.BubbleMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.launch
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

    private var micGranted = true
    private val silentStt = object : SpeechToText {
        override suspend fun listen(request: ListenRequest, onPartial: (String) -> Unit, onLevel: (Float) -> Unit) = ListenResult.NoMatch
        override fun cancel() = Unit
    }
    private val quietTts = object : TextToSpeech {
        override suspend fun speak(text: String, languageTag: String, rate: Float) = true
        override fun stop() = Unit
    }

    private fun controller(): AssistantController {
        val engine = AssistantEngine(
            gateway, silentStt, quietTts, LocalInterpreter(), { null }, { null }, { }, { SessionConfig() },
            CoroutineScope(dispatcher),
        )
        return AssistantController(gateway, engine) { micGranted }
    }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `panel lists current screen elements`() = runTest(dispatcher) {
        val controller = controller()
        controller.onMicLongPress()
        runCurrent()
        assertTrue(controller.state.value.panelOpen)
        assertEquals(3, controller.state.value.panelElements.size)
    }

    @Test
    fun `element taps map to the right action`() = runTest(dispatcher) {
        val controller = controller()
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
    fun `tap without microphone permission asks for it instead of starting`() = runTest(dispatcher) {
        micGranted = false
        val controller = controller()
        val effects = mutableListOf<OverlayEffect>()
        val job = launch { controller.effects.collect { effects += it } }
        runCurrent()
        controller.onMicTap()
        runCurrent()
        assertEquals(listOf<OverlayEffect>(OverlayEffect.RequestMicPermission), effects)
        assertEquals(false, controller.sessionActive)
        job.cancel()
    }

    @Test
    fun `failures are shown as errors`() = runTest(dispatcher) {
        gateway.nextResult = ActionResult.Failure("Element vid:x is not on screen")
        val controller = controller()
        controller.onBack()
        runCurrent()
        assertEquals(BubbleMode.ERROR, controller.state.value.mode)
    }
}
