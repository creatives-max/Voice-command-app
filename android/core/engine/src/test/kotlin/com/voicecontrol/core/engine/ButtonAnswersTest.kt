package com.voicecontrol.core.engine

import com.voicecontrol.core.engine.port.SessionConfig
import com.voicecontrol.core.model.ElementKind
import com.voicecontrol.core.model.Language
import com.voicecontrol.core.model.ScreenAction
import com.voicecontrol.core.model.ScreenElement
import com.voicecontrol.core.model.ScreenSnapshot
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Screens without fields ask "Which button should I press?"; the answer is often just a button's name. */
class ButtonAnswersTest {
    private fun button(id: String, label: String) = ScreenElement("vid:$id", ElementKind.BUTTON, label)

    private val welcome = ScreenSnapshot(
        "com.bank",
        elements = listOf(button("login", "Login"), button("register", "Create account"), button("ok", "OK"), button("book", "Book")),
        signature = "welcome",
    )

    private fun TestScope.engine(screen: FakeScreen, stt: ScriptedStt, tts: RecordingTts, language: Language = Language.ENGLISH) = AssistantEngine(
        screen = screen, stt = stt, tts = tts, interpreter = LocalInterpreter(), flows = { null }, profiles = { null },
        recorder = { }, config = { SessionConfig(language = language, confirmValues = false) }, scope = this, screenSettleMillis = 10,
    )

    @Test
    fun `saying just the button's name presses it`() = runTest {
        val screen = FakeScreen(welcome)
        val tts = RecordingTts()
        engine(screen, ScriptedStt("login"), tts).start()
        advanceUntilIdle()
        assertTrue(ScreenAction.Click("vid:login") in screen.actions)
        assertFalse(tts.spoken.any { it.startsWith("Sorry, I didn't catch that") })
    }

    @Test
    fun `names in Hindi script and slightly different wording work`() = runTest {
        val hindi = FakeScreen(welcome)
        engine(hindi, ScriptedStt("लॉगिन"), RecordingTts(), Language.HINDI).start()
        advanceUntilIdle()
        assertTrue(ScreenAction.Click("vid:login") in hindi.actions)

        val wording = FakeScreen(welcome)
        engine(wording, ScriptedStt("create an account"), RecordingTts()).start()
        advanceUntilIdle()
        assertTrue(ScreenAction.Click("vid:register") in wording.actions)
    }

    @Test
    fun `a command word that is also a button's name presses that button`() = runTest {
        val screen = FakeScreen(welcome)
        engine(screen, ScriptedStt("OK"), RecordingTts()).start()
        advanceUntilIdle()
        // "OK" is also "yes", but the OK button is on screen; "ok" is not the "Book" button.
        assertEquals(listOf(ScreenAction.Click("vid:ok")), screen.actions.filterIsInstance<ScreenAction.Click>())
    }

    @Test
    fun `command words without such a button still work as commands`() = runTest {
        val bank = ScreenSnapshot("com.bank", elements = listOf(button("bank", "Bank"), button("pay", "Pay later")), signature = "home")
        val back = FakeScreen(bank)
        engine(back, ScriptedStt("back"), RecordingTts()).start()
        advanceUntilIdle()
        assertTrue(ScreenAction.Back in back.actions)
        assertFalse(back.actions.any { it is ScreenAction.Click })

        // "next" with no Next button presses the screen's main button.
        val next = FakeScreen(ScreenSnapshot("com.bank", elements = listOf(button("continue", "Continue"), button("help", "Get help")), signature = "intro"))
        engine(next, ScriptedStt("next"), RecordingTts()).start()
        advanceUntilIdle()
        assertTrue(ScreenAction.Click("vid:continue") in next.actions)
    }

    @Test
    fun `unknown names still ask again`() = runTest {
        val screen = FakeScreen(welcome)
        val tts = RecordingTts()
        engine(screen, ScriptedStt("weather today"), tts).start()
        advanceUntilIdle()
        assertFalse(screen.actions.any { it is ScreenAction.Click })
        assertTrue(tts.spoken.any { it.startsWith("Sorry, I didn't catch that") })
    }

    @Test
    fun `matching uses whole words`() {
        assertNull(ButtonMatcher.find("ok", listOf(button("book", "Book")), ButtonMatcher.STRICT))
        assertEquals("vid:ok", ButtonMatcher.find("ok", listOf(button("book", "Book"), button("ok", "OK")), ButtonMatcher.STRICT)?.id)
        assertEquals("vid:pay", ButtonMatcher.find("pay", listOf(button("pay", "Pay now")), ButtonMatcher.STRICT)?.id)
    }
}
