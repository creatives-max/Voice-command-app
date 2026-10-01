package com.voicecontrol.core.engine

import com.voicecontrol.core.engine.port.SessionConfig
import com.voicecontrol.core.engine.port.VisionDetector
import com.voicecontrol.core.model.Bounds
import com.voicecontrol.core.model.ElementKind
import com.voicecontrol.core.model.FieldType
import com.voicecontrol.core.model.Language
import com.voicecontrol.core.model.ScreenAction
import com.voicecontrol.core.model.ScreenElement
import com.voicecontrol.core.model.ScreenSnapshot
import com.voicecontrol.core.model.ScreenText
import com.voicecontrol.core.model.Screenshot
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Screen reader, undo, destructive-action guard and hybrid vision. */
class AccessibilityPowerTest {
    private val name = ScreenElement("vid:name", ElementKind.TEXT_FIELD, "Name", FieldType.NAME, bounds = Bounds(0, 100, 500, 150))
    private val pwd = ScreenElement("vid:pwd", ElementKind.TEXT_FIELD, "Password", FieldType.PASSWORD, isSensitive = true, bounds = Bounds(0, 200, 500, 250))
    private val terms = ScreenElement("vid:terms", ElementKind.CHECKBOX, "Accept terms", isChecked = false, bounds = Bounds(0, 300, 500, 350))
    private val delete = ScreenElement("vid:del", ElementKind.BUTTON, "Delete account", bounds = Bounds(0, 400, 500, 450))
    private val save = ScreenElement("vid:save", ElementKind.BUTTON, "Save", bounds = Bounds(0, 500, 500, 550))
    private val screenshot = Screenshot(ByteArray(1), 100, 100, 100, 100)

    private fun TestScope.engine(screen: FakeScreen, stt: ScriptedStt, tts: RecordingTts, cfg: SessionConfig = SessionConfig(confirmValues = false, askBeforeSubmit = false), vision: VisionDetector? = null) =
        AssistantEngine(
            screen = screen, stt = stt, tts = tts, interpreter = LocalInterpreter(), flows = { null }, profiles = { null }, recorder = { },
            config = { cfg }, scope = this, clock = { 1_000L }, newId = { "s" }, screenSettleMillis = 10, vision = vision,
        )

    @Test
    fun `reader reads text and controls in order, never private values, and activates items`() = runTest {
        val snapshot = ScreenSnapshot(
            "com.bank", title = "Profile", signature = "p",
            elements = listOf(name.copy(value = "Rahul"), pwd, terms, save),
            texts = listOf(ScreenText("Keep your details up to date", Bounds(0, 50, 500, 80))),
        )
        val items = ScreenReader.items(snapshot)
        val phrases = Phrases(Language.ENGLISH)
        assertEquals(
            listOf("Profile", "Keep your details up to date", "Name field, contains Rahul.", "Password field, private.", "Accept terms, not checked.", "Button, Save."),
            items.map { ScreenReader.describe(it, phrases) },
        )
        val screen = FakeScreen(snapshot)
        val tts = RecordingTts()
        val engine = engine(screen, ScriptedStt("next", "next", "next", "next", "select", "previous", "stop"), tts)
        engine.startReader()
        advanceUntilIdle()
        assertEquals("This screen has 6 items. Say next, previous, select, read all, or stop.", tts.spoken.first())
        assertTrue(ScreenAction.SetChecked("vid:terms", true) in screen.actions)
        assertTrue(tts.spoken.contains("Accept terms, checked."))
        assertTrue(tts.spoken.none { "hunter" in it })
    }

    @Test
    fun `read all, then answer a field from the reader`() = runTest {
        val screen = FakeScreen(ScreenSnapshot("com.app", signature = "s", elements = listOf(name, save), texts = listOf(ScreenText("Welcome", Bounds(0, 0, 10, 10)))))
        val tts = RecordingTts()
        engine(screen, ScriptedStt("read all", "previous", "select", "Meera", "stop"), tts).startReader()
        advanceUntilIdle()
        assertTrue(tts.spoken.containsAll(listOf("Name field, empty.", "Button, Save.", "End of screen.")))
        assertEquals("Meera", screen.valueOf("vid:name"))
    }

    @Test
    fun `undo restores the previous value and goes back to that field`() = runTest {
        val email = ScreenElement("vid:email", ElementKind.TEXT_FIELD, "Email", FieldType.EMAIL, bounds = Bounds(0, 200, 500, 250))
        val screen = FakeScreen(ScreenSnapshot("com.app", signature = "s", elements = listOf(name.copy(value = null), email)))
        val tts = RecordingTts()
        val engine = engine(screen, ScriptedStt("Rahul", "undo", "Rohan", "rohan at the rate gmail dot com"), tts)
        engine.start()
        advanceUntilIdle()
        assertTrue(tts.spoken.contains("Undid Name."))
        assertEquals("Rohan", screen.valueOf("vid:name"))
        assertEquals("rohan@gmail.com", screen.valueOf("vid:email"))
        // Undo outside a session (overlay button) reverts the last fill.
        assertTrue(engine.canUndo)
        assertEquals("Email", engine.undoLast())
        assertEquals("", screen.valueOf("vid:email"))
    }

    @Test
    fun `destructive buttons need confirmation, in any language`() = runTest {
        assertTrue(DestructiveActions.isDestructive("Delete account"))
        assertTrue(DestructiveActions.isDestructive("Pay ₹500"))
        assertTrue(DestructiveActions.isDestructive("भुगतान करें"))
        assertTrue(DestructiveActions.isDestructive("বাতিল"))
        assertFalse(DestructiveActions.isDestructive("Save"))
        assertFalse(DestructiveActions.isDestructive("Payment details"))
        assertFalse(DestructiveActions.isDestructive("Continue"))

        val screen = FakeScreen(ScreenSnapshot("com.app", signature = "s", elements = listOf(delete)))
        val tts = RecordingTts()
        engine(screen, ScriptedStt("delete account dabao", "no", "stop"), tts).start()
        advanceUntilIdle()
        assertFalse(ScreenAction.Click("vid:del") in screen.actions)
        assertTrue(tts.spoken.contains("Okay, I didn't press Delete account."))
    }

    @Test
    fun `hybrid vision repairs unlabelled fields and adds unreadable ones`() = runTest {
        val unlabelled = ScreenElement("vid:x", ElementKind.TEXT_FIELD, "Text field", null, bounds = Bounds(0, 100, 400, 150))
        val snapshot = ScreenSnapshot("com.web", signature = "w", elements = listOf(unlabelled, save))
        assertTrue(HybridVision.needsHelp(snapshot))
        val detected = listOf(
            ScreenElement("vision:e1", ElementKind.TEXT_FIELD, "Mobile number", FieldType.PHONE, bounds = Bounds(5, 102, 398, 152)),
            ScreenElement("vision:e2", ElementKind.TEXT_FIELD, "City", FieldType.TEXT, bounds = Bounds(0, 300, 400, 350)),
        )
        val merged = HybridVision.merge(snapshot.elements, detected)
        assertEquals(listOf("Mobile number", "City", "Save"), merged.elements.map { it.label })
        assertEquals(FieldType.PHONE, merged.elements.first().fieldType)
        assertEquals("vid:x", merged.elements.first().id)
        assertEquals(listOf("vision:e2"), merged.added.map { it.id })

        val screen = FakeScreen(snapshot).apply { shot = screenshot }
        var calls = 0
        val tts = RecordingTts()
        engine(
            screen, ScriptedStt("9876543210", "Pune", "no"), tts,
            SessionConfig(confirmValues = false, visionFallback = true),
            vision = { _, _, _ -> calls++; detected },
        ).start()
        advanceUntilIdle()
        assertEquals(1, calls)
        assertEquals("9876543210", screen.valueOf("vid:x"))
        assertTrue(tts.spoken.any { it == "What is your mobile number?" })
        // The added field is typed through a tap + type into the focused input.
        assertTrue(screen.actions.any { it is ScreenAction.TapAt && it.y == 325 })
        assertTrue(ScreenAction.TypeIntoFocused("Pune") in screen.actions)
    }
}
