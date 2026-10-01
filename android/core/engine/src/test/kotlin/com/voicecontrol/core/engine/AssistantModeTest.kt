package com.voicecontrol.core.engine

import com.voicecontrol.core.engine.port.AppDirectory
import com.voicecontrol.core.engine.port.InstalledApp
import com.voicecontrol.core.engine.port.SessionConfig
import com.voicecontrol.core.model.ElementKind
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

/** Screens without a form: the assistant asks what to do, opens apps, suggests, and stays awake. */
class AssistantModeTest {
    private fun button(id: String, label: String) = ScreenElement("vid:$id", ElementKind.BUTTON, label)

    private val home = ScreenSnapshot("com.launcher", elements = listOf(button("settings", "Settings")), signature = "home")
    private val chats = ScreenSnapshot("com.whatsapp", elements = listOf(button("chats", "Chats"), button("calls", "Calls"), button("status", "Status")), signature = "chats")
    private val bank = ScreenSnapshot("com.bank", elements = listOf(button("recharge", "Recharge"), button("pay", "Pay bill"), button("history", "History")), signature = "bank-home")
    private val recharge = ScreenSnapshot("com.bank", elements = listOf(button("prepaid", "Prepaid"), button("postpaid", "Postpaid")), signature = "recharge")

    private val apps = AppDirectory {
        listOf(InstalledApp("WhatsApp", "com.whatsapp"), InstalledApp("YouTube", "com.google.android.youtube"), InstalledApp("PhonePe", "com.phonepe.app"))
    }

    private fun TestScope.engine(screen: FakeScreen, stt: ScriptedStt, tts: RecordingTts) = AssistantEngine(
        screen = screen, stt = stt, tts = tts, interpreter = LocalInterpreter(), flows = { null }, profiles = { null },
        recorder = { }, config = { SessionConfig(confirmValues = false) }, scope = this, screenSettleMillis = 10, appDirectory = apps,
    )

    @Test
    fun `greets, opens the app the user names and keeps going there`() = runTest {
        val screen = FakeScreen(home).apply { onLaunch["com.whatsapp"] = chats }
        val tts = RecordingTts()
        engine(screen, ScriptedStt("WhatsApp kholo", "calls", "stop"), tts).start()
        advanceUntilIdle()

        assertEquals("What can I do for you?", tts.spoken.first())
        assertTrue(ScreenAction.LaunchApp("com.whatsapp") in screen.actions)
        assertTrue("Opening WhatsApp." in tts.spoken)
        assertTrue("What next?" in tts.spoken, "still listening in the opened app")
        assertTrue(ScreenAction.Click("vid:calls") in screen.actions)
    }

    @Test
    fun `app names said in Hindi script open the app`() = runTest {
        val screen = FakeScreen(home).apply { onLaunch["com.google.android.youtube"] = chats }
        engine(screen, ScriptedStt("यूट्यूब खोलो", "stop"), RecordingTts()).start()
        advanceUntilIdle()
        assertTrue(ScreenAction.LaunchApp("com.google.android.youtube") in screen.actions)
    }

    @Test
    fun `an unknown app is explained with what can be done instead`() = runTest {
        val screen = FakeScreen(bank)
        val tts = RecordingTts()
        engine(screen, ScriptedStt("open Snapchat", "stop"), tts).start()
        advanceUntilIdle()
        assertFalse(screen.actions.any { it is ScreenAction.LaunchApp })
        assertTrue(tts.spoken.any { it.startsWith("I couldn't find an app called snapchat") })
        assertTrue(tts.spoken.any { it.startsWith("Here you can: Recharge, Pay bill, History.") })
    }

    @Test
    fun `when the user is unsure it suggests what the screen offers, then acts`() = runTest {
        val screen = FakeScreen(bank).apply { onClick["vid:recharge"] = recharge }
        val tts = RecordingTts()
        engine(screen, ScriptedStt("pata nahi", "recharge", "postpaid", "stop"), tts).start()
        advanceUntilIdle()
        assertTrue(tts.spoken.any { it.startsWith("Here you can: Recharge, Pay bill, History.") })
        // After pressing Recharge the assistant stays awake on the next screen.
        assertTrue(ScreenAction.Click("vid:recharge") in screen.actions)
        assertTrue("What next?" in tts.spoken)
        assertTrue(ScreenAction.Click("vid:postpaid") in screen.actions)
    }

    @Test
    fun `apps are matched by how their names sound`() {
        val list = runCatching { kotlinx.coroutines.runBlocking { apps.apps() } }.getOrThrow() +
            listOf(InstalledApp("Phone", "com.android.dialer"), InstalledApp("Paytm", "net.one97.paytm"), InstalledApp("Gmail", "com.google.android.gm"))
        assertEquals("com.whatsapp", AppMatcher.find("व्हाट्सएप", list)?.packageName)
        assertEquals("com.phonepe.app", AppMatcher.find("फोनपे", list)?.packageName)
        assertEquals("com.android.dialer", AppMatcher.find("phone", list)?.packageName)
        assertEquals("net.one97.paytm", AppMatcher.find("पेटीएम", list)?.packageName)
        assertEquals("com.google.android.gm", AppMatcher.find("जीमेल", list)?.packageName)
        assertNull(AppMatcher.find("snapchat", list))
    }
}
