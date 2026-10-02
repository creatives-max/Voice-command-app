package com.voicecontrol.core.engine

import com.voicecontrol.core.engine.port.AgentAction
import com.voicecontrol.core.engine.port.AgentDecision
import com.voicecontrol.core.engine.port.AppDirectory
import com.voicecontrol.core.engine.port.GoalAgent
import com.voicecontrol.core.engine.port.InstalledApp
import com.voicecontrol.core.engine.port.SessionConfig
import com.voicecontrol.core.model.ElementKind
import com.voicecontrol.core.model.FieldType
import com.voicecontrol.core.model.ScreenAction
import com.voicecontrol.core.model.ScreenElement
import com.voicecontrol.core.model.ScreenSnapshot
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** "Mujhe bijli ka bill bharna hai": the helper operates the app and asks the user only what it needs. */
class GoalHelperTest {
    private fun button(id: String, label: String) = ScreenElement("vid:$id", ElementKind.BUTTON, label)
    private val consumer = ScreenElement("vid:consumer", ElementKind.TEXT_FIELD, "Consumer number", FieldType.NUMBER)
    private val upiPin = ScreenElement("vid:pin", ElementKind.TEXT_FIELD, "UPI PIN", FieldType.PIN, isSensitive = true)

    private val launcher = ScreenSnapshot("com.launcher", elements = listOf(button("cam", "Camera")), signature = "home")
    private val ppHome = ScreenSnapshot("com.phonepe.app", elements = listOf(button("electricity", "Electricity"), button("recharge", "Recharge")), signature = "pp-home")
    private val bill = ScreenSnapshot("com.phonepe.app", elements = listOf(consumer, button("fetch", "Fetch bill")), signature = "bill")
    private val pay = ScreenSnapshot("com.phonepe.app", elements = listOf(upiPin, button("pay", "Pay 540")), signature = "pay")
    private val done = ScreenSnapshot("com.phonepe.app", elements = listOf(button("ok", "Done")), signature = "done")

    private val apps = AppDirectory { listOf(InstalledApp("PhonePe", "com.phonepe.app")) }

    /** Plans like the server would: by screen, using what the user said (from the history). */
    private class Planner : GoalAgent {
        val seen = mutableListOf<Pair<String, List<String>>>()
        override suspend fun next(goal: String, screen: ScreenSnapshot, history: List<String>, language: com.voicecontrol.core.model.Language): AgentDecision {
            seen += screen.signature to history.toList()
            return when (screen.signature) {
                "home" -> AgentDecision(AgentAction.OPEN_APP, appName = "PhonePe", say = "PhonePe khol raha hoon.")
                "pp-home" -> AgentDecision(AgentAction.CLICK, "vid:electricity", say = "Bijli ka bill khol raha hoon.")
                "bill" -> if (screen.element("vid:consumer")?.value.isNullOrBlank()) {
                    AgentDecision(AgentAction.ASK, "vid:consumer", question = "Aapka consumer number kya hai?")
                } else {
                    AgentDecision(AgentAction.CLICK, "vid:fetch", say = "Bill dekh raha hoon.")
                }
                "pay" -> if (history.none { "themselves" in it }) {
                    AgentDecision(AgentAction.ASK, "vid:pin", question = "Apna UPI PIN boliye.")
                } else {
                    AgentDecision(AgentAction.CLICK, "vid:pay", confirm = true)
                }
                "done" -> AgentDecision(AgentAction.DONE, say = "540 rupaye ka bill bhar diya.")
                else -> AgentDecision(AgentAction.GIVE_UP)
            }
        }
    }

    private val learned = mutableListOf<Pair<String, com.voicecontrol.core.model.FlowDefinition>>()

    private fun TestScope.engine(screen: FakeScreen, stt: ScriptedStt, tts: RecordingTts, agent: GoalAgent?) = AssistantEngine(
        screen = screen, stt = stt, tts = tts, interpreter = LocalInterpreter(), flows = { null }, profiles = { null },
        recorder = { }, config = { SessionConfig(confirmValues = false) }, scope = this, screenSettleMillis = 10,
        appDirectory = apps, goalAgent = agent, goalMemory = { goal, flow -> learned += goal to flow; true },
    )

    @Test
    fun `does the whole job, asks only for the number, lets the user type the PIN, and confirms the payment`() = runTest {
        val screen = FakeScreen(launcher).apply {
            onLaunch["com.phonepe.app"] = ppHome
            onClick["vid:electricity"] = bill
            onClick["vid:fetch"] = pay
            onClick["vid:pay"] = done
        }
        val planner = Planner()
        val tts = RecordingTts()
        val stt = ScriptedStt("mujhe bijli ka bill bharna hai", "1234567", "next", "haan", "haan", "stop")
        engine(screen, stt, tts, planner).start()
        advanceUntilIdle()

        val acts = screen.actions
        assertTrue(ScreenAction.LaunchApp("com.phonepe.app") in acts)
        assertTrue(ScreenAction.Click("vid:electricity") in acts)
        assertTrue(ScreenAction.SetText("vid:consumer", "1234567") in acts)
        // The PIN is typed by the user; the phone only puts the cursor there.
        assertTrue(ScreenAction.Focus("vid:pin") in acts)
        assertFalse(acts.any { it is ScreenAction.SetText && it.elementId == "vid:pin" })
        assertTrue(ScreenAction.Click("vid:pay") in acts)
        assertEquals("done", screen.snapshot?.signature)
        assertTrue("Theek hai, main kar deta hoon. Kuch chahiye hoga to aapse pooch lunga." in tts.spoken || tts.spoken.any { it.startsWith("Sure, I'll do it") })
        assertTrue("Aapka consumer number kya hai?" in tts.spoken)
        assertTrue("540 rupaye ka bill bhar diya." in tts.spoken)
        assertTrue(planner.seen.flatMap { it.second }.any { "1234567" in it })

        // The way is remembered as a flow, started next time by the goal's words.
        assertTrue("Kya main ye tareeka yaad rakh loon? Agli baar bas boliye: mujhe bijli ka bill bharna hai." in tts.spoken || tts.spoken.any { it.startsWith("Shall I remember") })
        val (phrase, flow) = learned.single()
        assertEquals("mujhe bijli ka bill bharna hai", phrase)
        assertEquals("com.phonepe.app", flow.appPackage)
        assertEquals(
            listOf("vid:electricity", "vid:consumer", "vid:fetch", "vid:pin", "vid:pay"),
            flow.orderedSteps.filter { it.elementId.isNotEmpty() }.map { it.elementId },
        )
        // Names on screen are given to the recognizer.
        assertTrue(stt.requests.any { "Consumer number" in it.biasPhrases })
    }

    @Test
    fun `without the helper it says what it needs and keeps listening`() = runTest {
        val screen = FakeScreen(launcher)
        val tts = RecordingTts()
        engine(screen, ScriptedStt("mujhe bijli ka bill bharna hai", "stop"), tts, GoalAgent { _, _, _, _ -> null }).start()
        advanceUntilIdle()
        assertTrue(tts.spoken.any { it.startsWith("To do this for you I need the internet") }, tts.spoken.toString())
        assertFalse(screen.actions.any { it is ScreenAction.Click })
    }

    @Test
    fun `a plan that keeps pressing the same thing asks the user`() = runTest {
        val screen = FakeScreen(ppHome)
        val tts = RecordingTts()
        val stuck = GoalAgent { _, _, _, _ -> AgentDecision(AgentAction.CLICK, "vid:recharge") }
        engine(screen, ScriptedStt("I want to recharge my phone", "stop"), tts, stuck).start()
        advanceUntilIdle()
        assertTrue(tts.spoken.any { it.startsWith("I'm not sure what to press here") }, tts.spoken.toString())
    }

    @Test
    fun `the recognizer's other guesses are tried for button names`() = runTest {
        val screen = FakeScreen(ppHome)
        val stt = object : com.voicecontrol.core.engine.port.SpeechToText {
            var n = 0
            override suspend fun listen(
                request: com.voicecontrol.core.engine.port.ListenRequest,
                onPartial: (String) -> Unit,
                onLevel: (Float) -> Unit,
            ) = if (n++ == 0) com.voicecontrol.core.engine.port.ListenResult.Heard("ri charge", listOf("recharge")) else com.voicecontrol.core.engine.port.ListenResult.Heard("stop")
            override fun cancel() = Unit
        }
        AssistantEngine(
            screen = screen, stt = stt, tts = RecordingTts(), interpreter = LocalInterpreter(), flows = { null }, profiles = { null },
            recorder = { }, config = { SessionConfig(confirmValues = false) }, scope = this, screenSettleMillis = 10,
        ).start()
        advanceUntilIdle()
        assertTrue(ScreenAction.Click("vid:recharge") in screen.actions)
    }
}
