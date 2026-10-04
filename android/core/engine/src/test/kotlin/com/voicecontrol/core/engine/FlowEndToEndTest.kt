package com.voicecontrol.core.engine

import com.voicecontrol.core.engine.port.AgentAction
import com.voicecontrol.core.engine.port.AgentDecision
import com.voicecontrol.core.engine.port.AppDirectory
import com.voicecontrol.core.engine.port.GoalAgent
import com.voicecontrol.core.engine.port.InstalledApp
import com.voicecontrol.core.engine.port.SessionConfig
import com.voicecontrol.core.model.ElementKind
import com.voicecontrol.core.model.FieldType
import com.voicecontrol.core.model.FlowDefinition
import com.voicecontrol.core.model.ScreenAction
import com.voicecontrol.core.model.ScreenElement
import com.voicecontrol.core.model.ScreenSnapshot
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A grocery order end to end ("Maggi order karo"): taught by hand or learned from the helper, then run
 * again from the launcher, from the app's home screen, and from deep inside the app.
 */
class FlowEndToEndTest {
    private fun button(id: String, label: String) = ScreenElement("vid:$id", ElementKind.BUTTON, label)
    private val searchBox = ScreenElement("vid:q", ElementKind.TEXT_FIELD, "Search for products", FieldType.SEARCH)

    private val launcher = ScreenSnapshot("com.launcher", elements = listOf(button("zepto", "Zepto")), signature = "home")
    private val zHome = ScreenSnapshot("com.zepto", elements = listOf(button("search", "Search"), button("offers", "Offers")), signature = "z-home")
    private val zSearch = ScreenSnapshot("com.zepto", elements = listOf(searchBox), signature = "z-search")
    private val zResults = ScreenSnapshot("com.zepto", elements = listOf(button("maggi", "Maggi 2-Minute Noodles"), button("yippee", "Yippee Noodles")), signature = "z-results")
    private val zItem = ScreenSnapshot("com.zepto", elements = listOf(button("add", "Add to cart"), button("cart", "View cart")), signature = "z-item")
    private val zCart = ScreenSnapshot("com.zepto", elements = listOf(button("pay", "Pay Now")), signature = "z-cart")
    private val zPaid = ScreenSnapshot("com.zepto", elements = listOf(button("track", "Track order")), signature = "z-paid")

    private val apps = AppDirectory { listOf(InstalledApp("Zepto", "com.zepto")) }

    /** The real app: each tap or Enter opens the next screen. */
    private fun zepto(start: ScreenSnapshot) = FakeScreen(start).apply {
        onLaunch["com.zepto"] = zHome
        onClick["vid:zepto"] = zHome
        onClick["vid:search"] = zSearch
        onEnter["vid:q"] = zResults
        onClick["vid:maggi"] = zItem
        onClick["vid:add"] = zItem.copy(signature = "z-item-added")
        onClick["vid:cart"] = zCart
        onClick["vid:pay"] = zPaid
    }

    /** Everything a person does by hand while teaching, as the phone reports it. */
    private fun teachByHand(): FlowDefinition {
        val r = FlowRecorder(homePackages = { setOf("com.launcher") })
        r.onEvent(RecordedEvent.Screen(launcher))
        r.onEvent(RecordedEvent.Pressed("vid:zepto", on = launcher))
        r.onEvent(RecordedEvent.Screen(zHome))
        r.onEvent(RecordedEvent.Pressed("vid:search", on = zHome))
        r.onEvent(RecordedEvent.Screen(zSearch))
        r.onEvent(RecordedEvent.Typed("vid:q", on = zSearch))
        r.onEvent(RecordedEvent.Screen(zSearch.copy(elements = listOf(searchBox.copy(value = "maggi")))))
        r.onEvent(RecordedEvent.Screen(zResults))
        r.onEvent(RecordedEvent.Pressed("vid:maggi", on = zResults))
        r.onEvent(RecordedEvent.Screen(zItem))
        r.onEvent(RecordedEvent.Pressed("vid:add", on = zItem))
        r.onEvent(RecordedEvent.Screen(zItem.copy(signature = "z-item-added")))
        r.onEvent(RecordedEvent.Pressed("vid:cart", on = zItem.copy(signature = "z-item-added")))
        r.onEvent(RecordedEvent.Screen(zCart))
        r.onEvent(RecordedEvent.Pressed("vid:pay", on = zCart))
        r.onEvent(RecordedEvent.Screen(zPaid))
        return RecordingToFlow.build(r.recording(), "taught-maggi", 0, name = "Maggi order karo")
    }

    private fun TestScope.engine(screen: FakeScreen, stt: ScriptedStt, tts: RecordingTts, agent: GoalAgent? = null, learned: MutableList<FlowDefinition>? = null) =
        AssistantEngine(
            screen = screen, stt = stt, tts = tts, interpreter = LocalInterpreter(), flows = { null }, profiles = { null },
            recorder = { }, config = { SessionConfig(confirmValues = false) }, scope = this, screenSettleMillis = 10,
            appDirectory = apps, goalAgent = agent, goalMemory = learned?.let { list -> com.voicecontrol.core.engine.port.GoalMemory { _, f -> list += f; true } },
        )

    private val everyPress = listOf("vid:search", "vid:maggi", "vid:add", "vid:cart", "vid:pay")

    private fun pressesIn(screen: FakeScreen) = screen.actions.filterIsInstance<ScreenAction.Click>().map { it.elementId }

    @Test
    fun `a flow taught by hand keeps every step`() {
        val flow = teachByHand()
        val steps = flow.orderedSteps.filter { it.elementId.isNotEmpty() }.map { it.elementId }
        assertEquals(listOf("vid:search", "vid:q", "vid:maggi", "vid:add", "vid:cart", "vid:pay"), steps)
        assertEquals("com.zepto", flow.appPackage)
    }

    @Test
    fun `running the taught flow from the launcher does every step, asks the search words and confirms paying`() = runTest {
        val screen = zepto(launcher)
        val tts = RecordingTts()
        engine(screen, ScriptedStt("maggi", "haan", "stop"), tts).start(teachByHand())
        advanceUntilIdle()
        assertEquals(ScreenAction.LaunchApp("com.zepto"), screen.actions.first())
        assertTrue(ScreenAction.SetText("vid:q", "maggi") in screen.actions, screen.actions.toString())
        assertEquals(everyPress, pressesIn(screen), tts.spoken.toString())
        assertEquals("z-paid", screen.snapshot?.signature)
    }

    @Test
    fun `a pop-up in the way is closed by itself and the flow's own taps are done quietly`() = runTest {
        val rateUs = zResults.copy(
            elements = listOf(button("rate", "Rate us 5 stars"), button("notnow", "Not now")),
            signature = "z-rate-us",
        )
        val screen = zepto(zHome).apply {
            onEnter["vid:q"] = rateUs
            onClick["vid:notnow"] = zResults
        }
        val tts = RecordingTts()
        engine(screen, ScriptedStt("maggi", "haan", "stop"), tts).start(teachByHand())
        advanceUntilIdle()
        assertEquals(listOf("vid:search", "vid:notnow", "vid:maggi", "vid:add", "vid:cart", "vid:pay"), pressesIn(screen), tts.spoken.toString())
        assertEquals("z-paid", screen.snapshot?.signature)
        assertTrue(tts.spoken.none { it.startsWith("Pressed") && "Pay" !in it }, tts.spoken.toString())
    }

    @Test
    fun `running it while the app is already open on its home screen works the same`() = runTest {
        val screen = zepto(zHome)
        engine(screen, ScriptedStt("maggi", "haan", "stop"), RecordingTts()).start(teachByHand())
        advanceUntilIdle()
        assertEquals(everyPress, pressesIn(screen))
        assertEquals("z-paid", screen.snapshot?.signature)
    }

    @Test
    fun `running it while the app is open deep inside (on the cart) still starts from the beginning`() = runTest {
        val screen = zepto(zCart).apply {
            // Going back from the cart reaches the app's home screen.
            onBack = { _ -> zHome }
        }
        val tts = RecordingTts()
        engine(screen, ScriptedStt("maggi", "haan", "stop"), tts).start(teachByHand())
        advanceUntilIdle()
        assertEquals(everyPress, pressesIn(screen), tts.spoken.toString() + screen.actions)
        assertEquals("z-paid", screen.snapshot?.signature)
    }

    @Test
    fun `when Back doesn't get there, the app is opened afresh and the flow still runs`() = runTest {
        val screen = zepto(zCart) // Back does nothing on this screen
        engine(screen, ScriptedStt("maggi", "haan", "stop"), RecordingTts()).start(teachByHand())
        advanceUntilIdle()
        assertTrue(ScreenAction.LaunchApp("com.zepto", fresh = true) in screen.actions, screen.actions.toString())
        assertEquals(everyPress, pressesIn(screen))
        assertEquals("z-paid", screen.snapshot?.signature)
    }

    @Test
    fun `a job done by the helper is remembered with every step and runs again from the launcher`() = runTest {
        val learned = mutableListOf<FlowDefinition>()
        val helper = GoalAgent { _, s, _, _ ->
            when (s.signature) {
                "home" -> AgentDecision(AgentAction.OPEN_APP, appName = "Zepto")
                "z-home" -> AgentDecision(AgentAction.CLICK, "vid:search")
                "z-search" -> if (s.element("vid:q")?.value.isNullOrBlank()) AgentDecision(AgentAction.FILL, "vid:q", value = "maggi") else AgentDecision(AgentAction.CLICK, "vid:q")
                "z-results" -> AgentDecision(AgentAction.CLICK, "vid:maggi")
                "z-item" -> AgentDecision(AgentAction.CLICK, "vid:add")
                "z-item-added" -> AgentDecision(AgentAction.CLICK, "vid:cart")
                "z-cart" -> AgentDecision(AgentAction.CLICK, "vid:pay", confirm = true, question = "Maggi ke 14 rupaye pay kar doon?")
                else -> AgentDecision(AgentAction.DONE, say = "Order ho gaya.")
            }
        }
        val first = zepto(launcher)
        val tts = RecordingTts()
        engine(first, ScriptedStt("Zepto pe maggi order karo", "haan", "haan", "stop"), tts, helper, learned).start()
        advanceUntilIdle()
        assertTrue("Order ho gaya." in tts.spoken, tts.spoken.toString())
        val flow = learned.single()
        assertEquals(
            listOf("vid:search", "vid:q", "vid:maggi", "vid:add", "vid:cart", "vid:pay"),
            flow.orderedSteps.filter { it.elementId.isNotEmpty() }.map { it.elementId }.distinct(),
            flow.orderedSteps.toString(),
        )

        // Next time, the learned flow alone does the whole order (no AI needed).
        val again = zepto(launcher)
        engine(again, ScriptedStt("maggi", "haan", "stop"), RecordingTts()).start(flow)
        advanceUntilIdle()
        assertEquals(everyPress, pressesIn(again), again.actions.toString())
        assertEquals("z-paid", again.snapshot?.signature)
    }

    @Test
    fun `the item is found by name even when the list order changed, scrolling if needed`() = runTest {
        // Taught when Maggi was the second result ("item #1"); today it is fourth, below the fold.
        val row = { n: Int, label: String -> ScreenElement(if (n == 0) "vid:product" else "vid:product#$n", ElementKind.BUTTON, label) }
        val taughtResults = ScreenSnapshot("com.zepto", elements = listOf(row(0, "Yippee Noodles"), row(1, "Maggi 2-Minute Noodles")), isScrollable = true, signature = "z-results")
        val r = FlowRecorder()
        r.onEvent(RecordedEvent.Screen(taughtResults))
        r.onEvent(RecordedEvent.Pressed("vid:product#1", on = taughtResults))
        r.onEvent(RecordedEvent.Screen(zItem))
        r.onEvent(RecordedEvent.Pressed("vid:add", on = zItem))
        val flow = RecordingToFlow.build(r.recording(), "list", 0)

        val today = ScreenSnapshot("com.zepto", elements = listOf(row(0, "Top Ramen"), row(1, "Yippee Noodles")), isScrollable = true, signature = "z-results")
        val below = ScreenSnapshot("com.zepto", elements = listOf(row(0, "Wai Wai"), row(1, "Maggi 2-Minute Noodles")), isScrollable = true, signature = "z-results")
        val screen = FakeScreen(today).apply { onClick["vid:product#1"] = zItem; onClick["vid:add"] = zPaid }
        val scrolling = object : com.voicecontrol.core.engine.port.ScreenGateway by screen {
            override suspend fun perform(action: ScreenAction): com.voicecontrol.core.model.ActionResult {
                if (action is ScreenAction.Scroll) screen.snapshot = below
                return screen.perform(action)
            }
        }
        AssistantEngine(
            screen = scrolling, stt = ScriptedStt("haan", "stop"), tts = RecordingTts(), interpreter = LocalInterpreter(), flows = { null },
            profiles = { null }, recorder = { }, config = { SessionConfig(confirmValues = false) }, scope = this, screenSettleMillis = 10, appDirectory = apps,
        ).start(flow)
        advanceUntilIdle()
        // Yippee (the old position) is never pressed; Maggi is found below and pressed.
        val clicks = screen.actions.filterIsInstance<ScreenAction.Click>()
        assertTrue(screen.actions.any { it is ScreenAction.Scroll }, screen.actions.toString())
        assertEquals("vid:product#1", clicks.first().elementId)
        assertTrue(ScreenAction.Click("vid:add") in screen.actions, screen.actions.toString())
    }

    @Test
    fun `a learned order types the item from the request by itself next time`() = runTest {
        val learned = mutableListOf<FlowDefinition>()
        val helper = GoalAgent { _, s, _, _ ->
            when (s.signature) {
                "z-home" -> AgentDecision(AgentAction.CLICK, "vid:search")
                "z-search" -> if (s.element("vid:q")?.value.isNullOrBlank()) AgentDecision(AgentAction.FILL, "vid:q", value = "maggi") else AgentDecision(AgentAction.CLICK, "vid:q")
                "z-results" -> AgentDecision(AgentAction.CLICK, "vid:maggi")
                else -> AgentDecision(AgentAction.DONE, say = "Maggi khul gaya.")
            }
        }
        engine(zepto(zHome), ScriptedStt("Zepto pe maggi order karo", "haan", "stop"), RecordingTts(), helper, learned).start()
        advanceUntilIdle()
        val flow = learned.single()
        val search = flow.orderedSteps.single { it.elementId == "vid:q" }
        assertEquals("maggi", search.defaultValue)

        val again = zepto(launcher)
        val tts = RecordingTts()
        engine(again, ScriptedStt("haan", "stop"), tts).start(flow)
        advanceUntilIdle()
        assertTrue(ScreenAction.SetText("vid:q", "maggi") in again.actions, again.actions.toString())
        assertTrue(tts.spoken.none { it == "What shall I search for?" }, tts.spoken.toString())
        assertTrue(ScreenAction.Click("vid:maggi") in again.actions, again.actions.toString())
    }
}
