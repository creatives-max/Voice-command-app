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

    private fun TestScope.engine(screen: FakeScreen, stt: ScriptedStt, tts: RecordingTts, agent: GoalAgent?, smart: Boolean = false) = AssistantEngine(
        screen = screen, stt = stt, tts = tts, interpreter = LocalInterpreter(), flows = { null }, profiles = { null },
        recorder = { }, config = { SessionConfig(confirmValues = false, smartMode = smart) }, scope = this, screenSettleMillis = 10,
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

    @Test
    fun `a slow step says one moment once instead of going quiet`() = runTest {
        val screen = FakeScreen(ppHome).apply { onClick["vid:recharge"] = done }
        val tts = RecordingTts()
        val slow = GoalAgent { _, s, _, _ ->
            kotlinx.coroutines.delay(5_000)
            if (s.signature == "done") AgentDecision(AgentAction.DONE, say = "Ho gaya.") else AgentDecision(AgentAction.CLICK, "vid:recharge")
        }
        engine(screen, ScriptedStt("I want to recharge my phone", "stop"), tts, slow).start()
        advanceUntilIdle()
        assertEquals(1, tts.spoken.count { it == "One moment…" }, tts.spoken.toString())
        assertTrue("Ho gaya." in tts.spoken)
    }

    @Test
    fun `smart mode lets the AI fill a form, asking in its own words, without announcing or offering to remember`() = runTest {
        val screen = FakeScreen(bill).apply { onClick["vid:fetch"] = done }
        val tts = RecordingTts()
        val asked = mutableListOf<String>()
        val ai = GoalAgent { goal, s, _, _ ->
            asked += goal
            when {
                s.signature == "done" -> AgentDecision(AgentAction.DONE, say = "Bill mil gaya.")
                s.element("vid:consumer")?.value.isNullOrBlank() -> AgentDecision(AgentAction.ASK, "vid:consumer", question = "Bijli ke bill pe likha consumer number boliye.")
                else -> AgentDecision(AgentAction.CLICK, "vid:fetch")
            }
        }
        engine(screen, ScriptedStt("1234567", "stop"), tts, ai, smart = true).start()
        advanceUntilIdle()
        assertTrue("Bijli ke bill pe likha consumer number boliye." in tts.spoken, tts.spoken.toString())
        assertTrue(ScreenAction.SetText("vid:consumer", "1234567") in screen.actions)
        assertTrue(ScreenAction.Click("vid:fetch") in screen.actions)
        assertTrue("Bill mil gaya." in tts.spoken)
        assertEquals(AssistantEngine.SMART_FORM_GOAL, asked.first())
        assertFalse(tts.spoken.any { it.startsWith("Sure, I'll do it") || it.startsWith("Shall I remember") }, tts.spoken.toString())
        assertTrue(learned.isEmpty())
    }

    @Test
    fun `smart mode without the server carries on the built-in way`() = runTest {
        val screen = FakeScreen(bill)
        val tts = RecordingTts()
        engine(screen, ScriptedStt("1234567", "stop", "stop"), tts, GoalAgent { _, _, _, _ -> null }, smart = true).start()
        advanceUntilIdle()
        assertTrue(ScreenAction.SetText("vid:consumer", "1234567") in screen.actions, tts.spoken.toString())
        assertFalse(tts.spoken.any { it.startsWith("To do this for you I need the internet") })
    }

    @Test
    fun `smart mode sends any request to the AI but presses a named button itself`() = runTest {
        val screen = FakeScreen(ppHome).apply { onClick["vid:electricity"] = done; onClick["vid:recharge"] = done }
        val goals = mutableListOf<String>()
        val ai = GoalAgent { goal, s, _, _ ->
            goals += goal
            if (s.signature == "done") AgentDecision(AgentAction.DONE, say = "Ho gaya.") else AgentDecision(AgentAction.CLICK, "vid:electricity")
        }
        engine(screen, ScriptedStt("light wala bill", "stop"), RecordingTts(), ai, smart = true).start()
        advanceUntilIdle()
        assertEquals("light wala bill", goals.first())
        assertTrue(ScreenAction.Click("vid:electricity") in screen.actions)

        val screen2 = FakeScreen(ppHome).apply { onClick["vid:recharge"] = done }
        val goals2 = mutableListOf<String>()
        engine(screen2, ScriptedStt("Recharge", "stop"), RecordingTts(), GoalAgent { g, _, _, _ -> goals2 += g; null }, smart = true).start()
        advanceUntilIdle()
        assertTrue(ScreenAction.Click("vid:recharge") in screen2.actions)
        assertTrue(goals2.isEmpty())
    }

    @Test
    fun `the helper gets the saved details, and asks once in its own words before a payment`() = runTest {
        val screen = FakeScreen(pay).apply { onClick["vid:pay"] = done }
        val seen = mutableListOf<List<String>>()
        val ai = GoalAgent { _, s, history, _ ->
            seen += history
            if (s.signature == "done") AgentDecision(AgentAction.DONE, say = "Ho gaya.")
            else AgentDecision(AgentAction.CLICK, "vid:pay", confirm = true, question = "Bijli ke 540 rupaye bhar doon?")
        }
        val tts = RecordingTts()
        AssistantEngine(
            screen = screen, stt = ScriptedStt("haan", "stop"), tts = tts, interpreter = LocalInterpreter(), flows = { null },
            profiles = { com.voicecontrol.core.model.UserProfile(fullName = "Rahul Sharma", phone = "9876543210") },
            recorder = { }, config = { SessionConfig(confirmValues = false, smartMode = true) }, scope = this, screenSettleMillis = 10,
            appDirectory = apps, goalAgent = ai,
        ).start()
        advanceUntilIdle()
        assertTrue(seen.first().first().startsWith("Known about the user: name Rahul Sharma; mobile 9876543210"), seen.first().toString())
        assertEquals(1, tts.spoken.count { it == "Bijli ke 540 rupaye bhar doon?" }, tts.spoken.toString())
        assertFalse(tts.spoken.any { "Pay 540" in it && it.endsWith("?") }, tts.spoken.toString())
        // Already talking: no greeting after the job.
        assertFalse(tts.spoken.any { it.startsWith("Good morning") || it.startsWith("Good evening") || it.startsWith("Good afternoon") }, tts.spoken.toString())
        assertTrue(ScreenAction.Click("vid:pay") in screen.actions)
    }

    @Test
    fun `a payment the user agreed to is not asked about again on the PIN screen, and nothing is said before the question`() = runTest {
        val amount = ScreenSnapshot("com.phonepe.app", elements = listOf(button("pay", "Pay 540")), signature = "amount")
        val pinScreen = ScreenSnapshot("com.phonepe.app", elements = listOf(upiPin, button("confirmpay", "Confirm payment")), signature = "pin")
        val screen = FakeScreen(amount).apply { onClick["vid:pay"] = pinScreen; onClick["vid:confirmpay"] = done }
        val ai = GoalAgent { _, s, history, _ ->
            when (s.signature) {
                "amount" -> AgentDecision(AgentAction.CLICK, "vid:pay", say = "Payment kar raha hoon.", confirm = true, question = "540 rupaye bhar doon?")
                "pin" -> if (history.none { "themselves" in it }) AgentDecision(AgentAction.ASK, "vid:pin", question = "Apna PIN daaliye.")
                    else AgentDecision(AgentAction.CLICK, "vid:confirmpay")
                else -> AgentDecision(AgentAction.DONE, say = "Ho gaya.")
            }
        }
        val tts = RecordingTts()
        engine(screen, ScriptedStt("bill bhar do", "haan", "next", "stop"), tts, ai, smart = true).start()
        advanceUntilIdle()
        assertEquals(1, tts.spoken.count { it.endsWith("?") && ("540" in it || "Confirm payment" in it) }, tts.spoken.toString())
        assertFalse("Payment kar raha hoon." in tts.spoken)
        assertTrue(ScreenAction.Click("vid:confirmpay") in screen.actions, tts.spoken.toString())
    }

    @Test
    fun `with no Enter key the search button next to the box is pressed`() = runTest {
        val search = ScreenSnapshot(
            "com.shop",
            elements = listOf(ScreenElement("vid:q", ElementKind.TEXT_FIELD, "Search", FieldType.SEARCH), button("go", "Go")),
            signature = "search",
        )
        val screen = FakeScreen(search).apply { onClick["vid:go"] = done }
        val ai = GoalAgent { _, s, history, _ ->
            when {
                s.signature == "done" -> AgentDecision(AgentAction.DONE, say = "Mil gaya.")
                history.none { it.startsWith("Typed") } -> AgentDecision(AgentAction.FILL, "vid:q", value = "chawal")
                else -> AgentDecision(AgentAction.CLICK, "vid:q")
            }
        }
        engine(screen, ScriptedStt("chawal dhundo", "stop"), RecordingTts(), ai, smart = true).start()
        advanceUntilIdle()
        assertTrue(ScreenAction.PressEnter("vid:q") in screen.actions)
        assertTrue(ScreenAction.Click("vid:go") in screen.actions, screen.actions.toString())
    }

    @Test
    fun `stopping before paying still offers to remember the way up to there`() = runTest {
        val shop = ScreenSnapshot("com.zepto", elements = listOf(button("search", "Search"), button("maggi", "Maggi 2-minute noodles")), signature = "shop")
        val item = ScreenSnapshot("com.zepto", elements = listOf(button("add", "Add to cart"), button("cart", "View cart")), signature = "item")
        val cart = ScreenSnapshot("com.zepto", elements = listOf(button("paynow", "Pay Now")), signature = "cart")
        val screen = FakeScreen(shop).apply { onClick["vid:maggi"] = item; onClick["vid:add"] = item.copy(signature = "added"); onClick["vid:cart"] = cart }
        val ai = GoalAgent { _, s, _, _ ->
            when (s.signature) {
                "shop" -> AgentDecision(AgentAction.CLICK, "vid:maggi")
                "item" -> AgentDecision(AgentAction.CLICK, "vid:add")
                "added" -> AgentDecision(AgentAction.CLICK, "vid:cart")
                else -> AgentDecision(AgentAction.CLICK, "vid:paynow", confirm = true, question = "Maggi ke 14 rupaye pay kar doon?")
            }
        }
        val tts = RecordingTts()
        engine(screen, ScriptedStt("mujhe maggi order karni hai", "nahi", "haan", "stop"), tts, ai).start()
        advanceUntilIdle()
        assertFalse(ScreenAction.Click("vid:paynow") in screen.actions)
        assertTrue(tts.spoken.any { it.startsWith("Kya main ye tareeka yaad rakh loon?") || it.startsWith("Shall I remember") }, tts.spoken.toString())
        val (_, flow) = learned.single()
        assertEquals(listOf("vid:maggi", "vid:add", "vid:cart"), flow.orderedSteps.filter { it.elementId.isNotEmpty() }.map { it.elementId })
    }

    @Test
    fun `a name further down the list is found by scrolling, quietly`() = runTest {
        val top = ScreenSnapshot("com.zepto", elements = listOf(button("dal", "Toor dal"), button("rice", "Basmati rice")), isScrollable = true, signature = "top")
        val lower = ScreenSnapshot("com.zepto", elements = listOf(button("oil", "Sunflower oil"), button("maggi", "Maggi 2-minute noodles")), isScrollable = true, signature = "lower")
        val screen = object : com.voicecontrol.core.engine.port.ScreenGateway by FakeScreen(top) {
            val inner = FakeScreen(top).apply { onClick["vid:maggi"] = done }
            override suspend fun capture() = inner.capture()
            override suspend fun perform(action: ScreenAction): com.voicecontrol.core.model.ActionResult {
                if (action is ScreenAction.Scroll) inner.snapshot = lower
                return inner.perform(action)
            }
        }
        val tts = RecordingTts()
        AssistantEngine(
            screen = screen, stt = ScriptedStt("maggi", "stop"), tts = tts, interpreter = LocalInterpreter(), flows = { null }, profiles = { null },
            recorder = { }, config = { SessionConfig(confirmValues = false) }, scope = this, screenSettleMillis = 10,
        ).start()
        advanceUntilIdle()
        assertTrue(ScreenAction.Click("vid:maggi") in screen.inner.actions, tts.spoken.toString())
        assertTrue(tts.spoken.any { it.startsWith("Looking for maggi") }, tts.spoken.toString())
        assertFalse(tts.spoken.any { it.startsWith("New screen") || it == "What next?" }, tts.spoken.toString())
    }

    @Test
    fun `smart mode doesn't treat a video's comment box as a form`() = runTest {
        val video = ScreenSnapshot(
            "com.google.android.youtube",
            elements = listOf(button("like", "Like"), ScreenElement("vid:c", ElementKind.TEXT_FIELD, "Add a comment…")),
            signature = "video",
        )
        val goals = mutableListOf<String>()
        engine(FakeScreen(video), ScriptedStt("stop"), RecordingTts(), GoalAgent { g, _, _, _ -> goals += g; null }, smart = true).start()
        advanceUntilIdle()
        assertTrue(goals.none { it == AssistantEngine.SMART_FORM_GOAL }, goals.toString())
    }

    @Test
    fun `the helper is told when typing didn't land, and gets its own plan back`() = runTest {
        val form = ScreenSnapshot("com.shop", elements = listOf(consumer, button("go", "Submit")), signature = "form")
        // This app ignores typing: the field stays empty.
        val screen = object : com.voicecontrol.core.engine.port.ScreenGateway by FakeScreen(form) {
            val inner = FakeScreen(form)
            override suspend fun capture() = inner.capture()
            override suspend fun perform(action: ScreenAction) =
                if (action is ScreenAction.SetText) com.voicecontrol.core.model.ActionResult.Success.also { inner.actions += action } else inner.perform(action)
        }
        val histories = mutableListOf<List<String>>()
        val ai = GoalAgent { _, _, history, _ ->
            histories += history
            if (histories.size == 1) AgentDecision(AgentAction.FILL, "vid:consumer", value = "1234567", plan = "type number > press go")
            else AgentDecision(AgentAction.GIVE_UP, say = "Ye app likhne nahi de raha.")
        }
        AssistantEngine(
            screen = screen, stt = ScriptedStt("stop"), tts = RecordingTts(), interpreter = LocalInterpreter(),
            flows = { null }, profiles = { null }, recorder = { }, config = { SessionConfig(confirmValues = false, smartMode = true) }, scope = this,
            screenSettleMillis = 10, appDirectory = apps, goalAgent = ai,
        ).start()
        advanceUntilIdle()
        val second = histories[1]
        assertTrue(second.any { it == "Your plan: type number > press go" }, second.toString())
        assertTrue(second.any { it.endsWith("but the field still looks empty") }, second.toString())
    }

    @Test
    fun `a payment during a call comes with a scam warning, and remote-access apps aren't opened`() = runTest {
        val amount = ScreenSnapshot("com.phonepe.app", elements = listOf(button("pay", "Pay 540")), signature = "amount")
        val screen = FakeScreen(amount).apply { onClick["vid:pay"] = done }
        val ai = GoalAgent { _, s, _, _ ->
            if (s.signature == "done") AgentDecision(AgentAction.DONE, say = "Ho gaya.")
            else AgentDecision(AgentAction.CLICK, "vid:pay", confirm = true, question = "540 rupaye bhar doon?")
        }
        val onCall = object : com.voicecontrol.core.engine.port.PhoneActions {
            override suspend fun setAlarm(hour: Int, minute: Int) = false
            override suspend fun setTimer(seconds: Int) = false
            override suspend fun search(place: com.voicecontrol.core.nlp.SearchPlace, query: String) = false
            override suspend fun findContact(name: String) = com.voicecontrol.core.engine.port.ContactResult.NotFound
            override suspend fun call(number: String) = com.voicecontrol.core.engine.port.DialResult.FAILED
            override suspend fun whatsapp(number: String, text: String?) = false
            override suspend fun inCall() = true
        }
        val tts = RecordingTts()
        val anydesk = AppDirectory { listOf(InstalledApp("AnyDesk", "com.anydesk.anydeskandroid"), InstalledApp("PhonePe", "com.phonepe.app")) }
        AssistantEngine(
            screen = screen, stt = ScriptedStt("AnyDesk kholo", "bill bhar do", "nahi", "stop"), tts = tts, interpreter = LocalInterpreter(),
            flows = { null }, profiles = { null }, recorder = { }, config = { SessionConfig(confirmValues = false, smartMode = true) }, scope = this,
            screenSettleMillis = 10, appDirectory = anydesk, goalAgent = ai, phoneActions = onCall,
        ).start()
        advanceUntilIdle()
        assertFalse(screen.actions.any { it is ScreenAction.LaunchApp }, screen.actions.toString())
        assertTrue(tts.spoken.any { it.startsWith("I won't open AnyDesk") }, tts.spoken.toString())
        assertTrue(tts.spoken.any { it.startsWith("Careful: a call is going on.") && it.endsWith("540 rupaye bhar doon?") }, tts.spoken.toString())
        assertFalse(ScreenAction.Click("vid:pay") in screen.actions)
    }

    @Test
    fun `a finished job leaves a note for the app, and the next job gets it`() = runTest {
        val notes = HashMap<String, MutableList<String>>()
        val memory = object : com.voicecontrol.core.engine.port.GoalMemory {
            override suspend fun learn(goal: String, flow: com.voicecontrol.core.model.FlowDefinition) = true
            override suspend fun notes(appPackage: String) = notes[appPackage].orEmpty()
            override suspend fun addNote(appPackage: String, note: String) { notes.getOrPut(appPackage) { mutableListOf() } += note }
        }
        val seen = mutableListOf<List<String>>()
        val ai = GoalAgent { _, s, history, _ ->
            seen += history
            if (s.signature == "done") AgentDecision(AgentAction.DONE, say = "Ho gaya.") else AgentDecision(AgentAction.CLICK, "vid:recharge")
        }
        fun session(stt: ScriptedStt) = AssistantEngine(
            screen = FakeScreen(ppHome).apply { onClick["vid:recharge"] = done }, stt = stt, tts = RecordingTts(), interpreter = LocalInterpreter(),
            flows = { null }, profiles = { null }, recorder = { }, config = { SessionConfig(confirmValues = false) }, scope = this,
            screenSettleMillis = 10, appDirectory = apps, goalAgent = ai, goalMemory = memory,
        )
        session(ScriptedStt("I want to recharge my phone", "nahi", "stop")).start()
        advanceUntilIdle()
        assertEquals(listOf("To I want to recharge my phone: Recharge"), notes["com.phonepe.app"]?.toList())
        seen.clear()
        session(ScriptedStt("I want to recharge my phone", "nahi", "stop")).start()
        advanceUntilIdle()
        assertTrue(seen.first().any { it == "Tips for this app from earlier jobs: To I want to recharge my phone: Recharge" }, seen.first().toString())
    }
}
