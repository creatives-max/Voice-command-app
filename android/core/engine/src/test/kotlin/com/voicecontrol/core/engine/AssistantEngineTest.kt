package com.voicecontrol.core.engine

import com.voicecontrol.core.engine.port.SessionConfig
import com.voicecontrol.core.model.ElementKind
import com.voicecontrol.core.model.FieldType
import com.voicecontrol.core.model.FlowDefinition
import com.voicecontrol.core.model.FlowStep
import com.voicecontrol.core.model.Language
import com.voicecontrol.core.model.RunStatus
import com.voicecontrol.core.model.ScreenAction
import com.voicecontrol.core.model.ScreenElement
import com.voicecontrol.core.model.ScreenSnapshot
import com.voicecontrol.core.model.SessionSummary
import com.voicecontrol.core.model.StepAction
import com.voicecontrol.core.model.StepOutcome
import com.voicecontrol.core.model.UserProfile
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AssistantEngineTest {

    private val name = ScreenElement("vid:name", ElementKind.TEXT_FIELD, "Full name", FieldType.NAME)
    private val email = ScreenElement("vid:email", ElementKind.TEXT_FIELD, "Email", FieldType.EMAIL)
    private val phone = ScreenElement("vid:phone", ElementKind.TEXT_FIELD, "Mobile number", FieldType.PHONE)
    private val password = ScreenElement("vid:pwd", ElementKind.TEXT_FIELD, "Password", FieldType.PASSWORD, isSensitive = true)
    private val terms = ScreenElement("vid:terms", ElementKind.CHECKBOX, "I accept the terms", isChecked = false)
    private val submit = ScreenElement("vid:submit", ElementKind.BUTTON, "Create account")
    private val form = ScreenSnapshot("com.shop", elements = listOf(name, email, phone, submit), signature = "form")

    private val recorded = mutableListOf<SessionSummary>()

    private fun TestScope.engine(
        screen: FakeScreen,
        stt: ScriptedStt,
        tts: RecordingTts = RecordingTts(),
        flow: FlowDefinition? = null,
        profile: UserProfile? = null,
        cfg: SessionConfig = SessionConfig(confirmValues = false),
        vision: com.voicecontrol.core.engine.port.VisionDetector? = null,
    ) = AssistantEngine(
        screen = screen,
        stt = stt,
        tts = tts,
        interpreter = LocalInterpreter(),
        flows = { flow },
        profiles = { profile },
        recorder = { recorded += it },
        config = { cfg },
        scope = this,
        clock = { 1_000L },
        newId = { "session-1" },
        screenSettleMillis = 10,
        vision = vision,
    )

    @Test
    fun `fills every field from speech and presses submit`() = runTest {
        val screen = FakeScreen(form)
        val engine = engine(screen, ScriptedStt("my name is rahul sharma", "rahul at the rate gmail dot com", "nine eight seven six five four three two one zero", "haan"))
        engine.start()
        advanceUntilIdle()

        assertEquals("Rahul Sharma", screen.valueOf("vid:name"))
        assertEquals("rahul@gmail.com", screen.valueOf("vid:email"))
        assertEquals("9876543210", screen.valueOf("vid:phone"))
        assertTrue(ScreenAction.Click("vid:submit") in screen.actions)
        assertFalse(engine.state.value.active)

        val summary = recorded.single()
        assertEquals(RunStatus.COMPLETED, summary.status)
        assertEquals(listOf(StepOutcome.FILLED, StepOutcome.FILLED, StepOutcome.FILLED, StepOutcome.CLICKED), summary.screens.single().steps.map { it.outcome })
    }

    @Test
    fun `invalid answers are re-asked and skip moves on`() = runTest {
        val screen = FakeScreen(form)
        val tts = RecordingTts()
        val engine = engine(screen, ScriptedStt("skip", "not an email", "rahul at the rate gmail dot com", "next", "no"), tts)
        engine.start()
        advanceUntilIdle()

        assertNull(screen.valueOf("vid:name"))
        assertEquals("rahul@gmail.com", screen.valueOf("vid:email"))
        assertTrue(tts.spoken.any { it.contains("doesn't look like an email") })
        assertFalse(ScreenAction.Click("vid:submit") in screen.actions)
    }

    @Test
    fun `previous goes back to the earlier field`() = runTest {
        val screen = FakeScreen(form.copy(elements = listOf(name, email)))
        val engine = engine(screen, ScriptedStt("Rahul", "previous", "Amit", "amit at the rate yahoo dot com"))
        engine.start()
        advanceUntilIdle()
        assertEquals("Amit", screen.valueOf("vid:name"))
        assertEquals("amit@yahoo.com", screen.valueOf("vid:email"))
    }

    @Test
    fun `sensitive fields are never filled by voice`() = runTest {
        val screen = FakeScreen(ScreenSnapshot("com.bank", elements = listOf(password), signature = "login"))
        val stt = ScriptedStt("hunter22", "next")
        val engine = engine(screen, stt)
        engine.start()
        advanceUntilIdle()
        assertTrue(screen.actions.none { it is ScreenAction.SetText })
        assertEquals(ScreenAction.Focus("vid:pwd"), screen.actions.first())
        assertEquals(StepOutcome.MANUAL, recorded.single().screens.single().steps.single().outcome)
    }

    @Test
    fun `saved flow controls order questions defaults skips and auto submit`() = runTest {
        val flow = FlowDefinition(
            id = "flow-1", version = 3, appPackage = "com.shop", name = "Signup", screenSignature = "form",
            steps = listOf(
                FlowStep("s1", 0, "vid:phone", "Mobile number", ElementKind.TEXT_FIELD, FieldType.PHONE, question = "Apna phone number bataiye"),
                FlowStep("s2", 1, "vid:name", "Full name", ElementKind.TEXT_FIELD, FieldType.NAME, skip = true, defaultValue = "Guest"),
                FlowStep("s3", 2, "vid:email", "Email", ElementKind.TEXT_FIELD, FieldType.EMAIL, skip = true),
                FlowStep("s4", 3, "vid:submit", "Create account", ElementKind.BUTTON, action = StepAction.CLICK, skip = true),
            ),
        )
        val screen = FakeScreen(form)
        val tts = RecordingTts()
        val engine = engine(screen, ScriptedStt("98765 43210"), tts, flow = flow)
        engine.start()
        advanceUntilIdle()

        assertTrue(tts.spoken.contains("Apna phone number bataiye"))
        assertEquals("9876543210", screen.valueOf("vid:phone"))
        assertEquals("Guest", screen.valueOf("vid:name"))
        assertNull(screen.valueOf("vid:email"))
        assertTrue(ScreenAction.Click("vid:submit") in screen.actions)
        val screenRecord = recorded.single().screens.single()
        assertEquals("flow-1", screenRecord.flowId)
        assertEquals(3, screenRecord.flowVersion)
    }

    @Test
    fun `profile suggestion is used when user says yes`() = runTest {
        val screen = FakeScreen(form.copy(elements = listOf(email)))
        val tts = RecordingTts()
        val engine = engine(screen, ScriptedStt("haan"), tts, profile = UserProfile(email = "me@example.com"))
        engine.start()
        advanceUntilIdle()
        assertEquals("me@example.com", screen.valueOf("vid:email"))
        assertTrue(tts.spoken.first { it.contains("Say yes") }.contains("me@example.com"))
    }

    @Test
    fun `toggles respond to yes and no`() = runTest {
        val screen = FakeScreen(ScreenSnapshot("com.shop", elements = listOf(terms), signature = "t"))
        val engine = engine(screen, ScriptedStt("yes"))
        engine.start()
        advanceUntilIdle()
        assertEquals(ScreenAction.SetChecked("vid:terms", true), screen.actions.single())
    }

    @Test
    fun `pressing a button by name and continuing on the next screen`() = runTest {
        val otp = ScreenElement("vid:city", ElementKind.TEXT_FIELD, "City", FieldType.TEXT)
        val second = ScreenSnapshot("com.shop", elements = listOf(otp), signature = "second")
        val screen = FakeScreen(form).apply { onClick["vid:submit"] = second }
        val engine = engine(screen, ScriptedStt("press create account", "Mumbai"))
        engine.start()
        advanceUntilIdle()
        assertTrue(ScreenAction.Click("vid:submit") in screen.actions)
        assertEquals("Mumbai", screen.valueOf("vid:city"))
        assertEquals(2, recorded.single().screens.size)
    }

    @Test
    fun `stop command ends the session as stopped`() = runTest {
        val screen = FakeScreen(form)
        val engine = engine(screen, ScriptedStt("ruko"))
        engine.start()
        advanceUntilIdle()
        assertTrue(screen.actions.isEmpty())
        assertFalse(engine.state.value.active)
    }

    @Test
    fun `repeated silence pauses the session`() = runTest {
        val screen = FakeScreen(form)
        val tts = RecordingTts()
        val engine = engine(screen, ScriptedStt(), tts)
        engine.start()
        advanceUntilIdle()
        assertFalse(engine.state.value.active)
        assertTrue(tts.spoken.any { it.contains("pause") })
    }

    @Test
    fun `hindi session speaks hindi and recognizes hindi`() = runTest {
        val screen = FakeScreen(form.copy(elements = listOf(name)))
        val tts = RecordingTts()
        val stt = ScriptedStt("मेरा नाम राहुल है")
        val engine = engine(screen, stt, tts, cfg = SessionConfig(language = Language.HINDI, transliterate = false, confirmValues = false))
        engine.start()
        advanceUntilIdle()
        assertEquals("राहुल", screen.valueOf("vid:name"))
        assertEquals("hi-IN", stt.requests.first().languageTag)
        assertTrue(tts.spoken.any { it.contains("बताइए") })
    }

    @Test
    fun `screen without fields offers its buttons`() = runTest {
        val home = ScreenSnapshot("com.shop", elements = listOf(ScreenElement("vid:cart", ElementKind.BUTTON, "Cart"), submit), signature = "home")
        val screen = FakeScreen(home)
        val engine = engine(screen, ScriptedStt("cart dabao"))
        engine.start()
        advanceUntilIdle()
        assertEquals(ScreenAction.Click("vid:cart"), screen.actions.single())
    }

    @Test
    fun `apps without readable nodes are operated through vision taps`() = runTest {
        val screen = FakeScreen(ScreenSnapshot("com.game", elements = emptyList())).apply {
            shot = com.voicecontrol.core.model.Screenshot(ByteArray(4), 540, 1200, 1080, 2400)
        }
        val detected = listOf(
            ScreenElement("vision:e1", ElementKind.TEXT_FIELD, "Player name", FieldType.NAME, bounds = com.voicecontrol.core.model.Bounds(100, 200, 500, 300)),
            ScreenElement("vision:e2", ElementKind.BUTTON, "Start", bounds = com.voicecontrol.core.model.Bounds(400, 2000, 700, 2100)),
        )
        val engine = engine(
            screen,
            ScriptedStt("Rahul", "haan"),
            cfg = SessionConfig(confirmValues = false, visionFallback = true),
            vision = { _, pkg, _ -> if (pkg == "com.game") detected else null },
        )
        engine.start()
        advanceUntilIdle()
        assertEquals(
            listOf(ScreenAction.TapAt(300, 250), ScreenAction.TypeIntoFocused("Rahul"), ScreenAction.TapAt(550, 2050)),
            screen.actions,
        )
    }

    @Test
    fun `vision is not used unless enabled`() = runTest {
        val screen = FakeScreen(ScreenSnapshot("com.game", elements = emptyList())).apply {
            shot = com.voicecontrol.core.model.Screenshot(ByteArray(4), 540, 1200, 1080, 2400)
        }
        val tts = RecordingTts()
        val engine = engine(screen, ScriptedStt(), tts, vision = { _, _, _ -> error("must not be called") })
        engine.start()
        advanceUntilIdle()
        assertTrue(tts.spoken.any { it.contains("can't read") })
    }
}
