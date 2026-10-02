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
}
