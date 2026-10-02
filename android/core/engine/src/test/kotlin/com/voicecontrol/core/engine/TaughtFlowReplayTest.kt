package com.voicecontrol.core.engine

import com.voicecontrol.core.engine.port.SessionConfig
import com.voicecontrol.core.model.ElementKind
import com.voicecontrol.core.model.ScreenAction
import com.voicecontrol.core.model.ScreenElement
import com.voicecontrol.core.model.ScreenSnapshot
import com.voicecontrol.core.model.StepAction
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A flow taught by doing ("open YouTube, search, play") is saved whole and replays every screen. */
class TaughtFlowReplayTest {
    private fun button(id: String, label: String) = ScreenElement("vid:$id", ElementKind.BUTTON, label)
    private val query = ScreenElement("vid:q", ElementKind.TEXT_FIELD, "Search YouTube")

    private val launcher = ScreenSnapshot("com.launcher", elements = listOf(button("yt", "YouTube"), button("wa", "WhatsApp")), signature = "home")
    private val ytHome = ScreenSnapshot("com.yt", elements = listOf(button("search", "Search"), button("shorts", "Shorts")), signature = "yt-home")
    private val ytSearch = ScreenSnapshot("com.yt", elements = listOf(query), signature = "yt-search")
    private val results = ScreenSnapshot("com.yt", elements = listOf(button("song", "Song A"), button("song2", "Song B")), signature = "yt-results")
    private val player = ScreenSnapshot("com.yt", elements = listOf(button("like", "Like")), signature = "player")

    /** What the phone reports while the user does it by hand; presses often arrive after the next screen. */
    private fun teach(): Recording {
        val r = FlowRecorder(homePackages = { setOf("com.launcher") })
        r.onEvent(RecordedEvent.Screen(launcher))
        r.onEvent(RecordedEvent.Screen(ytHome))
        r.onEvent(RecordedEvent.Pressed("vid:yt", on = launcher)) // the tap that opened YouTube, reported late
        r.onEvent(RecordedEvent.Pressed("vid:search", on = ytHome))
        r.onEvent(RecordedEvent.Screen(ytSearch))
        r.onEvent(RecordedEvent.Typed("vid:q", on = ytSearch))
        r.onEvent(RecordedEvent.Screen(ytSearch.copy(elements = listOf(query.copy(value = "arijit songs")))))
        r.onEvent(RecordedEvent.Screen(results))
        r.onEvent(RecordedEvent.Screen(player))
        r.onEvent(RecordedEvent.Pressed("vid:song", on = results)) // arrives after the player opened
        return r.recording()
    }

    @Test
    fun `every screen is saved, with the tap on the screen it was made on`() {
        val recording = teach()
        assertEquals(listOf("yt-home", "yt-search", "yt-results"), recording.screens.map { it.snapshot.signature })
        val flow = RecordingToFlow.build(recording, "t", 0)
        assertEquals("com.yt", flow.appPackage)
        assertEquals(
            listOf(StepAction.CLICK, StepAction.NEXT_SCREEN, StepAction.FILL, StepAction.NEXT_SCREEN, StepAction.CLICK),
            flow.orderedSteps.map { it.action },
        )
        // Taught taps replay on their own; the last one is confirmed.
        assertEquals(listOf(true, false), flow.orderedSteps.filter { it.action == StepAction.CLICK }.map { it.skip })
    }

    @Test
    fun `a tap that only got to the app is not part of the flow`() {
        val r = FlowRecorder()
        r.onEvent(RecordedEvent.Screen(launcher))
        r.onEvent(RecordedEvent.Pressed("vid:yt"))
        r.onEvent(RecordedEvent.Screen(ytHome))
        r.onEvent(RecordedEvent.Pressed("vid:search"))
        assertEquals(listOf("yt-home"), r.recording().screens.map { it.snapshot.signature })
    }

    private fun TestScope.engine(screen: FakeScreen, stt: ScriptedStt, tts: RecordingTts) = AssistantEngine(
        screen = screen, stt = stt, tts = tts, interpreter = LocalInterpreter(), flows = { null }, profiles = { null },
        recorder = { }, config = { SessionConfig(confirmValues = false) }, scope = this, screenSettleMillis = 10,
    )

    @Test
    fun `running the taught flow goes through all its screens`() = runTest {
        val flow = RecordingToFlow.build(teach(), "t", 0)
        val screen = FakeScreen(launcher).apply {
            onLaunch["com.yt"] = ytHome
            onClick["vid:search"] = ytSearch
            onEnter["vid:q"] = results // the search box has no button: Enter is pressed
            onClick["vid:song"] = player
        }
        val tts = RecordingTts()
        engine(screen, ScriptedStt("tum hi ho", "yes", "stop"), tts).start(flow)
        advanceUntilIdle()

        val acts = screen.actions
        assertEquals(ScreenAction.LaunchApp("com.yt"), acts.first())
        assertTrue(ScreenAction.Click("vid:search") in acts, "taught tap replayed without asking")
        assertTrue(ScreenAction.SetText("vid:q", "tum hi ho") in acts)
        assertTrue(ScreenAction.PressEnter("vid:q") in acts)
        assertTrue(ScreenAction.Click("vid:song") in acts)
        assertEquals("player", screen.snapshot?.signature)
        assertFalse(tts.spoken.take(3).any { it == "What can I do for you?" }, "the flow ran instead of asking")
    }
}
