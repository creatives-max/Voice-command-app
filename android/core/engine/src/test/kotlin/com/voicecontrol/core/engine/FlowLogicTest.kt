package com.voicecontrol.core.engine

import com.voicecontrol.core.engine.port.SessionConfig
import com.voicecontrol.core.model.ElementKind
import com.voicecontrol.core.model.FieldType
import com.voicecontrol.core.model.FlowDefinition
import com.voicecontrol.core.model.FlowStep
import com.voicecontrol.core.model.FlowVariables
import com.voicecontrol.core.model.RepeatSpec
import com.voicecontrol.core.model.RunStatus
import com.voicecontrol.core.model.ScreenAction
import com.voicecontrol.core.model.ScreenElement
import com.voicecontrol.core.model.ScreenSnapshot
import com.voicecontrol.core.model.SessionSummary
import com.voicecontrol.core.model.StepAction
import com.voicecontrol.core.model.StepOutcome
import com.voicecontrol.core.model.UserProfile
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Conditions, variables, computed values, templates, READ, REPEAT and multi-screen / cross-app flows. */
class FlowLogicTest {

    private val married = ScreenElement("vid:married", ElementKind.CHECKBOX, "Married", isChecked = false)
    private val spouse = ScreenElement("vid:spouse", ElementKind.TEXT_FIELD, "Spouse name", FieldType.NAME)
    private val first = ScreenElement("vid:first", ElementKind.TEXT_FIELD, "First name", FieldType.NAME)
    private val last = ScreenElement("vid:last", ElementKind.TEXT_FIELD, "Last name", FieldType.NAME)
    private val display = ScreenElement("vid:display", ElementKind.TEXT_FIELD, "Display name", FieldType.TEXT)
    private val submit = ScreenElement("vid:submit", ElementKind.BUTTON, "Submit")

    private val recorded = mutableListOf<SessionSummary>()

    private fun TestScope.engine(screen: FakeScreen, stt: ScriptedStt, tts: RecordingTts, flow: FlowDefinition?, profile: UserProfile? = null) =
        AssistantEngine(
            screen = screen,
            stt = stt,
            tts = tts,
            interpreter = LocalInterpreter(),
            flows = { flow },
            profiles = { profile },
            recorder = { recorded += it },
            config = { SessionConfig(confirmValues = false, askBeforeSubmit = false) },
            scope = this,
            clock = { 1_000L },
            newId = { "session-1" },
            screenSettleMillis = 10,
        )

    private fun step(id: String, order: Int, e: ScreenElement, action: StepAction = StepAction.FILL) =
        FlowStep(id, order, e.id, e.label, e.kind, e.fieldType, action = action)

    private fun logic(id: String, order: Int, action: StepAction, label: String = "") =
        FlowStep(id, order, "", label, ElementKind.BUTTON, action = action)

    private fun flow(signature: String, vararg steps: FlowStep, pkg: String = "com.form") =
        FlowDefinition("flow-1", 1, pkg, "Form", signature, steps.toList())

    @Test
    fun `condition true asks the step, condition false fills the else value`() = runTest {
        val screen = FakeScreen(ScreenSnapshot("com.form", elements = listOf(married, spouse, submit), signature = "s1"))
        val f = flow(
            "s1",
            step("a", 0, married, StepAction.TOGGLE).copy(variable = "married"),
            step("b", 1, spouse).copy(condition = "yes(married)", elseValue = "'N/A'"),
            step("c", 2, submit, StepAction.CLICK).copy(skip = true),
        )
        engine(screen, ScriptedStt("no"), RecordingTts(), f).start()
        advanceUntilIdle()
        assertEquals(false, screen.snapshot?.element("vid:married")?.isChecked)
        assertEquals("N/A", screen.valueOf("vid:spouse"))
        assertEquals(StepOutcome.DEFAULT_FILLED, recorded.single().screens.single().steps.first { it.elementId == "vid:spouse" }.outcome)

        recorded.clear()
        val screen2 = FakeScreen(ScreenSnapshot("com.form", elements = listOf(married, spouse, submit), signature = "s1"))
        val tts = RecordingTts()
        engine(screen2, ScriptedStt("haan", "Priya"), tts, f).start()
        advanceUntilIdle()
        assertEquals("Priya", screen2.valueOf("vid:spouse"))
    }

    @Test
    fun `computed values, set variable and question templates use earlier answers and the profile`() = runTest {
        val screen = FakeScreen(ScreenSnapshot("com.form", elements = listOf(first, last, display, submit), signature = "s1"))
        val tts = RecordingTts()
        val f = flow(
            "s1",
            step("a", 0, first).copy(variable = "first", valueExpression = "profile.first_name"),
            step("b", 1, last).copy(question = "{first}, what is your last name?"),
            logic("v", 2, StepAction.SET_VARIABLE, "full").copy(variable = "full", valueExpression = "concat(first, ' ', last_name)"),
            step("c", 3, display).copy(valueExpression = "upper(full)"),
            step("d", 4, submit, StepAction.CLICK).copy(skip = true),
        )
        engine(screen, ScriptedStt("Sharma"), tts, f, UserProfile(fullName = "Rahul Kumar")).start()
        advanceUntilIdle()
        assertEquals("Rahul", screen.valueOf("vid:first"))
        assertEquals("Sharma", screen.valueOf("vid:last"))
        assertEquals("RAHUL SHARMA", screen.valueOf("vid:display"))
        assertTrue(tts.spoken.any { it.startsWith("Rahul, what is your last name?") })
        assertEquals("last_name", FlowVariables.slug("Last name", 1))
    }

    @Test
    fun `read stores on-screen text and conditions see existing screen values`() = runTest {
        val plan = ScreenElement("vid:plan", ElementKind.TEXT_FIELD, "Plan", FieldType.TEXT, value = "Gold")
        val gst = ScreenElement("vid:gst", ElementKind.TEXT_FIELD, "GST number", FieldType.TEXT)
        val screen = FakeScreen(ScreenSnapshot("com.form", elements = listOf(plan, gst, submit), signature = "s1"))
        val f = flow(
            "s1",
            step("r", 0, plan, StepAction.READ).copy(variable = "tier"),
            step("g", 1, gst).copy(condition = "tier == 'silver'"),
            step("x", 2, display).copy(condition = "plan == 'gold'", valueExpression = "'unused'"),
            step("c", 3, submit, StepAction.CLICK).copy(skip = true),
        )
        engine(screen, ScriptedStt(), RecordingTts(), f).start()
        advanceUntilIdle()
        assertNull(screen.valueOf("vid:gst"))
        assertEquals(RunStatus.COMPLETED, recorded.single().status)
        assertTrue(ScreenAction.Click("vid:submit") in screen.actions)
    }

    @Test
    fun `repeat asks for each item and presses add another between items`() = runTest {
        val add = ScreenElement("vid:add", ElementKind.BUTTON, "Add item")
        fun row(i: Int) = ScreenElement("vid:item$i", ElementKind.TEXT_FIELD, "Item name", FieldType.TEXT)
        val one = ScreenSnapshot("com.list", elements = listOf(row(1), add, submit), signature = "list1")
        val two = ScreenSnapshot("com.list", elements = listOf(row(1).copy(value = "Rice"), row(2), add, submit), signature = "list2")
        val screen = FakeScreen(one).apply { onClick["vid:add"] = two }
        val tts = RecordingTts()
        val f = flow(
            "list1",
            FlowStep("item", 0, "vid:item1", "Item name", ElementKind.TEXT_FIELD, FieldType.TEXT, question = "Item {index}?", variable = "item"),
            logic("loop", 1, StepAction.REPEAT, "item").copy(repeat = RepeatSpec(listOf("item"), addMoreElementId = "vid:add", maxIterations = 5)),
            step("c", 2, submit, StepAction.CLICK).copy(skip = true),
            pkg = "com.list",
        )
        engine(screen, ScriptedStt("Rice", "haan", "Dal", "nahi"), tts, f).start()
        advanceUntilIdle()
        assertEquals("Rice", screen.valueOf("vid:item1"))
        assertEquals("Dal", screen.valueOf("vid:item2"))
        assertEquals(listOf(ScreenAction.Click("vid:add")), screen.actions.filter { it == ScreenAction.Click("vid:add") })
        assertTrue(tts.spoken.contains("Item 2?"))
        assertTrue(tts.spoken.any { it == "Add another item?" })
        assertTrue(ScreenAction.Click("vid:submit") in screen.actions)
        assertEquals(2, recorded.single().screens.single().steps.count { it.label == "Item name" })
    }

    @Test
    fun `repeat with a count expression does not ask`() = runTest {
        val count = ScreenElement("vid:count", ElementKind.TEXT_FIELD, "Number of children", FieldType.NUMBER)
        val child = ScreenElement("vid:child", ElementKind.TEXT_FIELD, "Child name", FieldType.NAME)
        val screen = FakeScreen(ScreenSnapshot("com.form", elements = listOf(count, child, submit), signature = "s1"))
        val tts = RecordingTts()
        val f = flow(
            "s1",
            step("n", 0, count).copy(variable = "kids"),
            step("child", 1, child),
            logic("loop", 2, StepAction.REPEAT, "child").copy(repeat = RepeatSpec(listOf("child"), countExpression = "kids")),
            step("c", 3, submit, StepAction.CLICK).copy(skip = true),
        )
        engine(screen, ScriptedStt("2", "Aarav", "Anaya"), tts, f).start()
        advanceUntilIdle()
        assertEquals("Anaya", screen.valueOf("vid:child"))
        assertTrue(tts.spoken.none { it.startsWith("Add another") })
        assertEquals(2, tts.spoken.count { it.startsWith("Please say Child name") })
    }

    @Test
    fun `multi-screen flow waits for the next screen and keeps variables`() = runTest {
        val pin = ScreenElement("vid:pin", ElementKind.TEXT_FIELD, "Pincode", FieldType.PINCODE)
        val confirm = ScreenElement("vid:confirm", ElementKind.TEXT_FIELD, "Confirm name", FieldType.TEXT)
        val next = ScreenElement("vid:next", ElementKind.BUTTON, "Next")
        val done = ScreenElement("vid:done", ElementKind.BUTTON, "Finish")
        val screenB = ScreenSnapshot("com.form", elements = listOf(confirm, pin, done), signature = "s2")
        val screen = FakeScreen(ScreenSnapshot("com.form", elements = listOf(first, next), signature = "s1")).apply { onClick["vid:next"] = screenB }
        val f = flow(
            "s1",
            step("a", 0, first).copy(variable = "name"),
            step("b", 1, next, StepAction.CLICK).copy(skip = true),
            logic("w", 2, StepAction.NEXT_SCREEN),
            step("c", 3, confirm).copy(valueExpression = "name"),
            step("d", 4, pin).copy(defaultValue = "110001", skip = true),
            step("e", 5, done, StepAction.CLICK).copy(skip = true),
        )
        engine(screen, ScriptedStt("Meera"), RecordingTts(), f).start()
        advanceUntilIdle()
        assertEquals("Meera", screen.valueOf("vid:confirm"))
        assertEquals("110001", screen.valueOf("vid:pin"))
        assertTrue(ScreenAction.Click("vid:done") in screen.actions)
        assertEquals(2, recorded.single().screens.size)
        assertEquals(RunStatus.COMPLETED, recorded.single().status)
    }

    @Test
    fun `cross-app flow opens the other app and fills it with earlier answers`() = runTest {
        val amount = ScreenElement("vid:amount", ElementKind.TEXT_FIELD, "Amount", FieldType.AMOUNT)
        val note = ScreenElement("pay:note", ElementKind.TEXT_FIELD, "Note", FieldType.TEXT)
        val pay = ScreenElement("pay:go", ElementKind.BUTTON, "Pay")
        val payApp = ScreenSnapshot("com.pay", elements = listOf(note, pay), signature = "pay")
        val screen = FakeScreen(ScreenSnapshot("com.shop", elements = listOf(amount), signature = "cart")).apply { onLaunch["com.pay"] = payApp }
        val tts = RecordingTts()
        val f = flow(
            "cart",
            step("a", 0, amount).copy(variable = "amount"),
            logic("o", 1, StepAction.OPEN_APP, "PayApp").copy(appPackage = "com.pay"),
            step("n", 2, note).copy(valueExpression = "concat('Order ', amount)"),
            step("p", 3, pay, StepAction.CLICK).copy(skip = true),
            pkg = "com.shop",
        )
        engine(screen, ScriptedStt("500"), tts, f).start()
        advanceUntilIdle()
        assertTrue(ScreenAction.LaunchApp("com.pay") in screen.actions)
        assertEquals("Order 500", screen.valueOf("pay:note"))
        assertTrue(ScreenAction.Click("pay:go") in screen.actions)
        assertTrue(tts.spoken.contains("Opening PayApp."))
    }

    @Test
    fun `missing next screen stops the flow as failed`() = runTest {
        val screen = FakeScreen(ScreenSnapshot("com.form", elements = listOf(first), signature = "s1"))
        val tts = RecordingTts()
        val f = flow("s1", step("a", 0, first), logic("w", 1, StepAction.NEXT_SCREEN).copy(waitSeconds = 2), step("b", 2, last))
        engine(screen, ScriptedStt("Meera", "no"), tts, f).start()
        advanceUntilIdle()
        assertEquals(RunStatus.FAILED, recorded.single().status)
        assertTrue(tts.spoken.any { it.startsWith("The next screen didn't open") })
    }

    @Test
    fun `preselected flow starting with open app launches it first`() = runTest {
        val note = ScreenElement("pay:note", ElementKind.TEXT_FIELD, "Note", FieldType.TEXT)
        val screen = FakeScreen(ScreenSnapshot("com.launcher", elements = listOf(submit), signature = "home"))
            .apply { onLaunch["com.pay"] = ScreenSnapshot("com.pay", elements = listOf(note), signature = "pay") }
        val f = flow("pay", logic("o", 0, StepAction.OPEN_APP).copy(appPackage = "com.pay"), step("n", 1, note).copy(valueExpression = "'hello'"), pkg = "com.pay")
        engine(screen, ScriptedStt(), RecordingTts(), null).start(f)
        advanceUntilIdle()
        assertEquals("hello", screen.valueOf("pay:note"))
    }

    @Test
    fun `a flow run on demand opens its app and emits a value-free log`() = runTest {
        val screen = FakeScreen(ScreenSnapshot("com.launcher", elements = listOf(submit), signature = "home"))
            .apply { onLaunch["com.form"] = ScreenSnapshot("com.form", title = "Signup", elements = listOf(first, submit), signature = "s1") }
        val events = mutableListOf<EngineEvent>()
        val engine = engine(screen, ScriptedStt("Meera"), RecordingTts(), null)
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { engine.events.collect { events += it } }
        val f = flow("s1", step("a", 0, first), step("c", 1, submit, StepAction.CLICK).copy(skip = true))
        engine.start(f)
        advanceUntilIdle()
        collector.cancel()
        assertTrue(ScreenAction.LaunchApp("com.form") in screen.actions)
        assertEquals("Meera", screen.valueOf("vid:first"))
        val messages = events.map { it.message }
        assertEquals("Started “Form”", messages.first())
        assertTrue("Asking: First name" in messages)
        assertTrue("Filled: First name" in messages)
        assertTrue("Pressed: Submit" in messages)
        assertEquals(RunStatus.COMPLETED, events.last().status, messages.toString())
        assertTrue(messages.none { "Meera" in it })
    }
}
