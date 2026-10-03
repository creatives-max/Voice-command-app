package com.voicecontrol.core.engine

import com.voicecontrol.core.engine.port.ContactResult
import com.voicecontrol.core.engine.port.DialResult
import com.voicecontrol.core.engine.port.PhoneActions
import com.voicecontrol.core.engine.port.SessionConfig
import com.voicecontrol.core.model.ElementKind
import com.voicecontrol.core.model.Language
import com.voicecontrol.core.model.ScreenAction
import com.voicecontrol.core.model.ScreenElement
import com.voicecontrol.core.model.ScreenSnapshot
import com.voicecontrol.core.nlp.SearchPlace
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The assistant does everyday phone jobs by voice and talks like a person while doing them. */
class PersonalAssistantTest {
    private val home = ScreenSnapshot("com.launcher", elements = listOf(ScreenElement("vid:cam", ElementKind.BUTTON, "Camera")), signature = "home")
    private val chat = ScreenSnapshot(
        "com.whatsapp",
        elements = listOf(ScreenElement("vid:msg", ElementKind.TEXT_FIELD, "Message"), ScreenElement("vid:send", ElementKind.BUTTON, "Send")),
        signature = "chat",
    )

    private class Phone(val screen: FakeScreen, val chat: ScreenSnapshot) : PhoneActions {
        val done = mutableListOf<String>()
        var contacts = mapOf("rahul" to "9876543210", "mummy" to "9123456780")
        override suspend fun setAlarm(hour: Int, minute: Int) = true.also { done += "alarm $hour:$minute" }
        override suspend fun setTimer(seconds: Int) = true.also { done += "timer $seconds" }
        override suspend fun search(place: SearchPlace, query: String) = true.also { done += "search $place $query" }
        override suspend fun findContact(name: String): ContactResult =
            contacts[name]?.let { ContactResult.Found(name.replaceFirstChar(Char::uppercase), it) } ?: ContactResult.NotFound
        override suspend fun call(number: String) = DialResult.CALLING.also { done += "call $number" }
        override suspend fun whatsapp(number: String, text: String?) = true.also {
            done += "whatsapp $number $text"
            screen.snapshot = chat
        }
    }

    private val noon = LocalDateTime.of(2026, 10, 2, 18, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun TestScope.engine(screen: FakeScreen, stt: ScriptedStt, tts: RecordingTts, phone: PhoneActions, language: Language = Language.HINGLISH) = AssistantEngine(
        screen = screen, stt = stt, tts = tts, interpreter = LocalInterpreter(), flows = { null }, profiles = { null },
        recorder = { }, config = { SessionConfig(language = language, confirmValues = false) }, scope = this, screenSettleMillis = 10,
        clock = { noon }, phoneActions = phone,
    )

    @Test
    fun `answers the time, sets an alarm and a timer, and keeps the conversation going`() = runTest {
        val screen = FakeScreen(home)
        val phone = Phone(screen, chat)
        val tts = RecordingTts()
        engine(screen, ScriptedStt("time kya hua", "subah 6 baje ka alarm laga do", "10 minute ka timer", "stop"), tts, phone).start()
        advanceUntilIdle()

        assertTrue(tts.spoken.any { it.startsWith("Abhi 6:30") }, tts.spoken.toString())
        assertTrue("Aur kuch?" in tts.spoken || "Aur kya kar doon aapke liye?" in tts.spoken)
        assertEquals(listOf("alarm 6:0", "timer 600"), phone.done)
        assertTrue(tts.spoken.any { it.startsWith("Theek hai, 6") && it.endsWith("ka alarm laga diya.") }, tts.spoken.toString())
        assertTrue("Theek hai, 10 minute ka timer shuru kar diya." in tts.spoken)
    }

    @Test
    fun `searches YouTube and calls a contact by name`() = runTest {
        val screen = FakeScreen(home)
        val phone = Phone(screen, chat)
        val tts = RecordingTts()
        engine(screen, ScriptedStt("YouTube pe Arijit Singh ke gaane chalao", "Rahul ko call karo", "Priya ko call karo", "stop"), tts, phone).start()
        advanceUntilIdle()

        assertEquals(listOf("search YOUTUBE arijit singh ke gaane", "call 9876543210"), phone.done)
        assertTrue("Rahul ko call laga raha hoon." in tts.spoken)
        assertTrue("Aapke contacts mein priya nahi mila." in tts.spoken)
    }

    @Test
    fun `writes a WhatsApp message, asks what to write, and sends it on yes`() = runTest {
        val screen = FakeScreen(home)
        val phone = Phone(screen, chat)
        val tts = RecordingTts()
        engine(screen, ScriptedStt("Mummy ko WhatsApp pe message bhejo", "main 10 minute mein aa raha hoon", "haan", "stop"), tts, phone).start()
        advanceUntilIdle()

        assertTrue("Mummy ko kya likhun?" in tts.spoken, tts.spoken.toString())
        assertEquals("whatsapp 9123456780 main 10 minute mein aa raha hoon", phone.done.single())
        assertTrue("Mummy ke liye message taiyaar hai. Bhej doon?" in tts.spoken)
        assertTrue(ScreenAction.Click("vid:send") in screen.actions)
        assertTrue("Mummy ko bhej diya." in tts.spoken)
    }

    private class Gadgets(screen: FakeScreen, chat: ScreenSnapshot) : PhoneActions by Phone(screen, chat) {
        val done = mutableListOf<String>()
        override suspend fun torch(on: Boolean) = true.also { done += "torch $on" }
        override suspend fun volume(change: com.voicecontrol.core.nlp.VolumeChange) = true.also { done += "volume $change" }
        override suspend fun battery() = com.voicecontrol.core.engine.port.BatteryInfo(76, charging = false)
        override suspend fun setReminder(hour: Int, minute: Int, text: String?) = true.also { done += "reminder $hour:$minute $text" }
        override suspend fun notifications() = listOf(
            com.voicecontrol.core.engine.port.NotificationInfo("WhatsApp", "Rahul", "Kab aa rahe ho?"),
            com.voicecontrol.core.engine.port.NotificationInfo("Messages", null, "Your bill is due"),
        )
    }

    @Test
    fun `controls the phone, reads messages and explains what it can do`() = runTest {
        val screen = FakeScreen(home)
        val phone = Gadgets(screen, chat)
        val tts = RecordingTts()
        engine(
            screen,
            ScriptedStt("torch jalao", "awaaz badhao", "battery kitni hai", "raat 9 baje dawai ki yaad dilana", "kya naya message aaya", "tum kya kya kar sakte ho", "stop"),
            tts,
            phone,
        ).start()
        advanceUntilIdle()

        assertEquals(listOf("torch true", "volume UP", "reminder 21:0 dawai"), phone.done)
        assertTrue("Torch jala di." in tts.spoken)
        assertTrue("Awaaz badha di." in tts.spoken)
        assertTrue("Battery 76 percent hai." in tts.spoken)
        assertTrue(tts.spoken.any { it.startsWith("Theek hai, 9") && it.endsWith("baje dawai ki yaad dila dunga.") }, tts.spoken.toString())
        assertTrue("Aapke 2 naye hain." in tts.spoken)
        assertTrue("WhatsApp par Rahul: Kab aa rahe ho?." in tts.spoken)
        assertTrue("Messages: Your bill is due." in tts.spoken)
        assertTrue(tts.spoken.any { it.startsWith("Main app khol sakta hoon") })
    }

    private class Guardian(screen: FakeScreen, chat: ScreenSnapshot, private val inner: Phone = Phone(screen, chat)) : PhoneActions by inner {
        var contact: String? = null
        val calls get() = inner.done
        override suspend fun emergencyContact() = contact
        override suspend fun setEmergencyContact(name: String) = true.also { contact = name.lowercase() }
    }

    @Test
    fun `sets an emergency contact by voice, calls them on bachao, reads a number, and asks before 112`() = runTest {
        val screen = FakeScreen(home)
        val phone = Guardian(screen, chat)
        val tts = RecordingTts()
        engine(screen, ScriptedStt("bachao", "nahi", "mera emergency contact Rahul hai", "bachao", "stop"), tts, phone).start()
        advanceUntilIdle()
        // No contact yet: it asks before calling 112, and "nahi" calls no one.
        assertTrue("Koi emergency contact set nahi hai. Kya 112 pe call karun?" in tts.spoken, tts.spoken.toString())
        assertTrue("Theek hai. Bachao ya emergency bolne pe main Rahul ko call karunga." in tts.spoken)
        assertTrue("Madad ke liye Rahul ko call kar raha hoon." in tts.spoken)
        assertEquals(listOf("call 9876543210"), phone.calls)

        val tts2 = RecordingTts()
        engine(screen, ScriptedStt("Mummy ka number kya hai", "stop"), tts2, phone).start()
        advanceUntilIdle()
        assertTrue("Mummy ka number hai 9 1 2 3 4 5 6 7 8 0." in tts2.spoken, tts2.spoken.toString())
    }

    private class Notebook(screen: FakeScreen, chat: ScreenSnapshot) : PhoneActions by Phone(screen, chat) {
        val notes = mutableListOf<String>()
        override suspend fun addNote(text: String) = notes.add(text)
        override suspend fun notes(): List<String> = notes.toList()
        override suspend fun clearNotes() = true.also { notes.clear() }
    }

    @Test
    fun `keeps notes by voice, reads them back and repeats the last answer`() = runTest {
        val screen = FakeScreen(home)
        val phone = Notebook(screen, chat)
        val tts = RecordingTts()
        engine(
            screen,
            ScriptedStt("note karo ki doodh lana hai", "note karo ki bijli ka bill bharna", "mere notes padho", "phir se bolo", "notes mita do", "stop"),
            tts,
            phone,
        ).start()
        advanceUntilIdle()
        assertEquals(2, tts.spoken.count { it == "Note kar liya." })
        // "phir se bolo" repeats the whole last answer.
        assertTrue("Aapke 2 note hain. 1. doodh lana hai. 2. bijli ka bill bharna." in tts.spoken, tts.spoken.toString())
        assertTrue("1. doodh lana hai." in tts.spoken)
        assertTrue("2. bijli ka bill bharna." in tts.spoken)
        assertTrue("Saare note mita diye." in tts.spoken)
        assertTrue(phone.notes.isEmpty())
    }

    @Test
    fun `usko call karo calls the person talked about just before`() = runTest {
        val screen = FakeScreen(home)
        val phone = Phone(screen, chat)
        val tts = RecordingTts()
        engine(screen, ScriptedStt("usko call karo", "Rahul ka number kya hai", "usko call karo", "stop"), tts, phone).start()
        advanceUntilIdle()
        assertTrue("Kisko? Naam bataiye." in tts.spoken, tts.spoken.toString())
        assertEquals(listOf("call 9876543210"), phone.done)
    }

    @Test
    fun `greets by name and names the app that is open`() = runTest {
        val screen = FakeScreen(ScreenSnapshot("com.whatsapp", elements = listOf(ScreenElement("vid:chats", ElementKind.BUTTON, "Chats")), signature = "wa-home"))
        val tts = RecordingTts()
        AssistantEngine(
            screen = screen, stt = ScriptedStt("stop"), tts = tts, interpreter = LocalInterpreter(), flows = { null },
            profiles = { com.voicecontrol.core.model.UserProfile(fullName = "Rahul Sharma") },
            recorder = { }, config = { SessionConfig(language = Language.HINGLISH, confirmValues = false) }, scope = this, screenSettleMillis = 10,
            clock = { noon }, phoneActions = Phone(screen, chat),
            appDirectory = { listOf(com.voicecontrol.core.engine.port.InstalledApp("WhatsApp", "com.whatsapp")) },
        ).start()
        advanceUntilIdle()
        assertEquals("Namaste Rahul ji! WhatsApp khula hai, bataiye kya karna hai?", tts.spoken.first())
    }
}
