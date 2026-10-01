package com.voicecontrol.core.engine

import com.voicecontrol.core.engine.port.SessionConfig
import com.voicecontrol.core.engine.port.ShortcutSource
import com.voicecontrol.core.model.ElementKind
import com.voicecontrol.core.model.FieldType
import com.voicecontrol.core.model.FlowDefinition
import com.voicecontrol.core.model.FlowStep
import com.voicecontrol.core.model.ScreenAction
import com.voicecontrol.core.model.ScreenElement
import com.voicecontrol.core.model.ScreenSnapshot
import com.voicecontrol.core.model.SessionSummary
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceShortcutsTest {
    private val bill = VoiceShortcut("Pay electricity bill", "flow-bill", "Electricity bill")
    private val taxi = VoiceShortcut("बिजली का बिल", "flow-bill-hi")
    private val cab = VoiceShortcut("book a cab", "flow-cab")
    private val all = listOf(bill, taxi, cab)

    @Test
    fun `matches the phrase with polite words, punctuation and small slips`() {
        assertEquals(bill, ShortcutMatcher.match("Pay electricity bill", all))
        assertEquals(bill, ShortcutMatcher.match("please pay electricity bill now!", all))
        assertEquals(bill, ShortcutMatcher.match("pay electricity bil", all))
        assertEquals(bill, ShortcutMatcher.match("can you pay electricity bill for me", all))
        assertEquals(taxi, ShortcutMatcher.match("बिजली का बिल करो", all))
        assertEquals(cab, ShortcutMatcher.match("Book a cab karo", all))
    }

    @Test
    fun `does not match unrelated speech or unclear ties`() {
        assertNull(ShortcutMatcher.match("what is the weather", all))
        assertNull(ShortcutMatcher.match("pay", all))
        assertNull(ShortcutMatcher.match("", all))
        val twins = listOf(VoiceShortcut("open bank", "a"), VoiceShortcut("open bank", "b"))
        assertNull(ShortcutMatcher.match("open bank", twins))
        // The same flow under two phrases is not a tie.
        assertEquals("a", ShortcutMatcher.match("open bank", listOf(VoiceShortcut("open bank", "a"), VoiceShortcut("Open bank!", "a")))?.flowId)
    }

    @Test
    fun `phrases are validated`() {
        assertNull(ShortcutMatcher.validate("Pay electricity bill"))
        assertNotNull(ShortcutMatcher.validate("a"))
        assertNotNull(ShortcutMatcher.validate("x".repeat(61)))
        assertNotNull(ShortcutMatcher.validate("Stop"))
        assertEquals("pay electricity bill", ShortcutMatcher.normalize("  Pay, Electricity   BILL. "))
        assertEquals(bill, ShortcutMatcher.conflict("please PAY electricity bill!", all))
        assertNull(ShortcutMatcher.conflict("pay water bill", all))
    }

    // --- In a voice session ---------------------------------------------------------------------

    private val home = ScreenSnapshot(
        "com.launcher",
        elements = listOf(ScreenElement("vid:phone", ElementKind.BUTTON, "Phone"), ScreenElement("vid:recharge", ElementKind.BUTTON, "Recharge")),
        signature = "home",
    )
    private val consumer = ScreenElement("vid:consumer", ElementKind.TEXT_FIELD, "Consumer number", FieldType.NUMBER)
    private val payForm = ScreenSnapshot("com.power", elements = listOf(consumer), signature = "pay-form")
    private val billFlow = FlowDefinition(
        id = "flow-bill",
        appPackage = "com.power",
        name = "Electricity bill",
        screenSignature = "pay-form",
        steps = listOf(FlowStep("c", 0, "vid:consumer", "Consumer number", ElementKind.TEXT_FIELD, FieldType.NUMBER)),
    )
    private val recorded = mutableListOf<SessionSummary>()

    private fun TestScope.engine(screen: FakeScreen, stt: ScriptedStt, tts: RecordingTts, flows: Map<String, FlowDefinition>) = AssistantEngine(
        screen = screen,
        stt = stt,
        tts = tts,
        interpreter = LocalInterpreter(),
        flows = { null },
        profiles = { null },
        recorder = { recorded += it },
        config = { SessionConfig(confirmValues = false) },
        scope = this,
        clock = { 1_000L },
        newId = { "s" },
        screenSettleMillis = 10,
        shortcuts = object : ShortcutSource {
            override suspend fun shortcuts() = all
            override suspend fun flow(flowId: String) = flows[flowId]
        },
    )

    @Test
    fun `saying a shortcut on a screen without a form runs its flow in its app`() = runTest {
        val screen = FakeScreen(home).apply { onLaunch["com.power"] = payForm }
        val tts = RecordingTts()
        val engine = engine(screen, ScriptedStt("please pay electricity bill", "123456"), tts, mapOf("flow-bill" to billFlow))
        engine.start()
        advanceUntilIdle()

        assertTrue(ScreenAction.LaunchApp("com.power") in screen.actions)
        assertEquals("123456", screen.valueOf("vid:consumer"))
        assertTrue(tts.spoken.any { it == "Starting Electricity bill." })
        assertEquals("flow-bill", recorded.single().screens.last().flowId)
        assertFalse(engine.state.value.active)
    }

    @Test
    fun `a visible button with the same name wins over a shortcut`() = runTest {
        val screen = FakeScreen(home)
        val tts = RecordingTts()
        val withPay = AssistantEngine(
            screen = screen, stt = ScriptedStt("press Recharge"), tts = tts, interpreter = LocalInterpreter(), flows = { null }, profiles = { null },
            recorder = { }, config = { SessionConfig(confirmValues = false) }, scope = this, screenSettleMillis = 10,
            shortcuts = object : ShortcutSource {
                override suspend fun shortcuts() = listOf(VoiceShortcut("recharge", "flow-bill"))
                override suspend fun flow(flowId: String) = billFlow
            },
        )
        withPay.start()
        advanceUntilIdle()
        assertTrue(ScreenAction.Click("vid:recharge") in screen.actions)
        assertFalse(screen.actions.any { it is ScreenAction.LaunchApp })
    }

    @Test
    fun `a shortcut whose flow is missing is explained`() = runTest {
        val screen = FakeScreen(home)
        val tts = RecordingTts()
        val engine = engine(screen, ScriptedStt("book a cab"), tts, emptyMap())
        engine.start()
        advanceUntilIdle()
        assertTrue(tts.spoken.any { it.startsWith("The flow for \"book a cab\"") })
        assertFalse(screen.actions.any { it is ScreenAction.LaunchApp })
    }
}
