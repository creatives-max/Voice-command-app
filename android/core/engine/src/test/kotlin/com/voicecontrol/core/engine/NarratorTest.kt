package com.voicecontrol.core.engine

import com.voicecontrol.core.engine.port.ScreenGateway
import com.voicecontrol.core.engine.port.SessionConfig
import com.voicecontrol.core.engine.port.TextToSpeech
import com.voicecontrol.core.model.ActionResult
import com.voicecontrol.core.model.Bounds
import com.voicecontrol.core.model.ElementKind
import com.voicecontrol.core.model.FieldType
import com.voicecontrol.core.model.ScreenAction
import com.voicecontrol.core.model.ScreenElement
import com.voicecontrol.core.model.ScreenSnapshot
import com.voicecontrol.core.model.ScreenText
import com.voicecontrol.core.model.Screenshot
import com.voicecontrol.core.model.ScrollDirection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NarratorTest {
    @Test
    fun `parses narrator commands in English, Hindi and Hinglish`() {
        assertEquals(NarratorCommand.Jump(ItemKind.BUTTON, true), NarratorCommands.parse("next button"))
        assertEquals(NarratorCommand.Jump(ItemKind.FIELD, true), NarratorCommands.parse("agla khana"))
        assertEquals(NarratorCommand.Jump(ItemKind.TEXT, false), NarratorCommands.parse("previous heading"))
        assertEquals(NarratorCommand.Jump(ItemKind.BUTTON, false), NarratorCommands.parse("पिछला बटन"))
        assertEquals(NarratorCommand.Edge(true), NarratorCommands.parse("Go to top"))
        assertEquals(NarratorCommand.Edge(false), NarratorCommands.parse("सबसे नीचे"))
        assertEquals(NarratorCommand.WhereAmI, NarratorCommands.parse("Where am I?"))
        assertEquals(NarratorCommand.WhereAmI, NarratorCommands.parse("main kahan hoon"))
        assertEquals(NarratorCommand.Find("delivery address"), NarratorCommands.parse("find delivery address"))
        assertEquals(NarratorCommand.Find("otp"), NarratorCommands.parse("OTP kahan hai"))
        assertEquals(NarratorCommand.Rate(true), NarratorCommands.parse("tez bolo"))
        assertEquals(NarratorCommand.Rate(false), NarratorCommands.parse("धीरे"))
        assertEquals(NarratorCommand.ReadEverything, NarratorCommands.parse("poora padho"))
        // Plain reader commands stay with the regular parser.
        assertNull(NarratorCommands.parse("next"))
        assertNull(NarratorCommands.parse("select"))
        assertNull(NarratorCommands.parse("read all"))
    }

    private class RateTts : TextToSpeech {
        val spoken = mutableListOf<Pair<String, Float>>()
        override suspend fun speak(text: String, languageTag: String, rate: Float): Boolean {
            spoken += text to rate
            return true
        }
        override fun stop() = Unit
    }

    /** A long page: each scroll down shows the next snapshot. */
    private class PagedScreen(private val pages: List<ScreenSnapshot>) : ScreenGateway {
        var page = 0
        val actions = mutableListOf<ScreenAction>()
        override val isAvailable: StateFlow<Boolean> = MutableStateFlow(true)
        override val screenChanges: Flow<ScreenSnapshot> = emptyFlow()
        override suspend fun capture(): ScreenSnapshot = pages[page]
        override suspend fun screenshot(): Screenshot? = null
        override suspend fun perform(action: ScreenAction): ActionResult {
            actions += action
            if (action is ScreenAction.Scroll && action.direction == ScrollDirection.DOWN && page < pages.lastIndex) page++
            return ActionResult.Success
        }
    }

    private fun TestScope.engine(screen: ScreenGateway, stt: ScriptedStt, tts: TextToSpeech) = AssistantEngine(
        screen = screen, stt = stt, tts = tts, interpreter = LocalInterpreter(), flows = { null }, profiles = { null }, recorder = { },
        config = { SessionConfig(confirmValues = false) }, scope = this, screenSettleMillis = 10,
    )

    private val form = ScreenSnapshot(
        "com.bank", title = "Profile", signature = "p",
        texts = listOf(ScreenText("Your details", Bounds(0, 50, 500, 80))),
        elements = listOf(
            ScreenElement("vid:name", ElementKind.TEXT_FIELD, "Name", FieldType.NAME, value = "Rahul", bounds = Bounds(0, 100, 500, 150)),
            ScreenElement("vid:city", ElementKind.TEXT_FIELD, "City", bounds = Bounds(0, 200, 500, 250)),
            ScreenElement("vid:terms", ElementKind.CHECKBOX, "Accept terms", isChecked = false, bounds = Bounds(0, 300, 500, 350)),
            ScreenElement("vid:save", ElementKind.BUTTON, "Save", bounds = Bounds(0, 400, 500, 450)),
        ),
    )

    @Test
    fun `jumps by kind, says where you are, finds words and changes speed`() = runTest {
        val tts = RateTts()
        engine(
            PagedScreen(listOf(form)),
            ScriptedStt("next button", "where am i", "find terms", "select", "faster", "go to top", "previous button", "stop"),
            tts,
        ).startReader()
        advanceUntilIdle()
        val said = tts.spoken.map { it.first }
        // After "next button" the current item is the Save button.
        assertEquals("Button, Save.", said[said.indexOf("This screen has 6 items. Say next, previous, select, read all, or stop.") + 2])
        assertTrue("Profile. Item 6 of 6. 2 fields, 1 empty, and 1 buttons." in said)
        assertTrue("Accept terms, checked." in said)
        val faster = tts.spoken.indexOfFirst { it.first == "Speaking faster." }
        assertEquals(1.25f, tts.spoken[faster].second)
        // "go to top" reads the title, at the new speed; nothing before it is a button.
        assertTrue(tts.spoken.drop(faster).any { it == "Profile" to 1.25f })
        assertTrue("There are no more of those." in said)
    }

    @Test
    fun `read everything keeps scrolling until the page ends`() = runTest {
        val page1 = ScreenSnapshot("com.news", elements = emptyList(), title = "News", signature = "n1", texts = listOf(ScreenText("Story one", Bounds(0, 100, 10, 110))))
        val page2 = page1.copy(signature = "n2", texts = listOf(ScreenText("Story one", Bounds(0, 10, 10, 20)), ScreenText("Story two", Bounds(0, 100, 10, 110))))
        val page3 = page1.copy(signature = "n3", texts = listOf(ScreenText("Story three", Bounds(0, 100, 10, 110))))
        val screen = PagedScreen(listOf(page1, page2, page3))
        val tts = RateTts()
        engine(screen, ScriptedStt("read everything", "stop"), tts).startReader()
        advanceUntilIdle()
        val said = tts.spoken.map { it.first }
        val start = said.indexOf("Story one")
        assertEquals(listOf("Story one", "Story two", "Story three", "End of screen."), said.subList(start, start + 4))
        // "Story one" is still on the second page but is read only once.
        assertEquals(1, said.count { it == "Story one" })
        assertEquals(3, screen.actions.count { it is ScreenAction.Scroll })
    }
}
