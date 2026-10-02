package com.voicecontrol.core.engine

import com.voicecontrol.core.engine.port.ContactResult
import com.voicecontrol.core.engine.port.ControlResult
import com.voicecontrol.core.engine.port.DialResult
import com.voicecontrol.core.engine.port.PhoneActions
import com.voicecontrol.core.engine.port.SessionConfig
import com.voicecontrol.core.model.ElementKind
import com.voicecontrol.core.model.Language
import com.voicecontrol.core.model.ScreenElement
import com.voicecontrol.core.model.ScreenSnapshot
import com.voicecontrol.core.model.SessionSummary
import com.voicecontrol.core.nlp.MediaKey
import com.voicecontrol.core.nlp.SearchPlace
import com.voicecontrol.core.nlp.SystemAction
import com.voicecontrol.core.nlp.VolumeChange
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Phone controls by voice, and assistant jobs kept in the history (so the dashboard shows them). */
class PhoneControlsTest {
    private val home = ScreenSnapshot("com.launcher", elements = listOf(ScreenElement("vid:cam", ElementKind.BUTTON, "Camera")), signature = "home")

    private class Phone : PhoneActions {
        val done = mutableListOf<String>()
        var brightnessResult = ControlResult.ASKED_PERMISSION
        override suspend fun setAlarm(hour: Int, minute: Int) = true
        override suspend fun setTimer(seconds: Int) = true
        override suspend fun search(place: SearchPlace, query: String) = true
        override suspend fun findContact(name: String): ContactResult = ContactResult.NotFound
        override suspend fun call(number: String) = DialResult.FAILED
        override suspend fun whatsapp(number: String, text: String?) = false
        override suspend fun system(action: SystemAction) = true.also { done += "system $action" }
        override suspend fun camera(video: Boolean, selfie: Boolean) = true.also { done += "camera $video $selfie" }
        override suspend fun brightness(change: VolumeChange) = brightnessResult.also { done += "brightness $change" }
        override suspend fun media(key: MediaKey) = true.also { done += "media $key" }
    }

    @Test
    fun `system actions, camera, music, brightness and sums by voice, all kept in the history`() = runTest {
        val screen = FakeScreen(home)
        val phone = Phone()
        val tts = RecordingTts()
        val recorded = mutableListOf<SessionSummary>()
        AssistantEngine(
            screen = screen,
            stt = ScriptedStt("screenshot lo", "selfie lo", "agla gaana", "brightness badhao", "25 guna 4 kitna hota hai", "100 ka 18 percent", "stop"),
            tts = tts, interpreter = LocalInterpreter(), flows = { null }, profiles = { null },
            recorder = { recorded += it }, config = { SessionConfig(language = Language.HINGLISH, confirmValues = false) },
            scope = this, screenSettleMillis = 10, phoneActions = phone,
        ).start()
        advanceUntilIdle()

        assertEquals(listOf("system SCREENSHOT", "camera false true", "media NEXT", "brightness UP"), phone.done)
        assertTrue("Screenshot le liya." in tts.spoken)
        assertTrue("Camera khol raha hoon." in tts.spoken)
        assertTrue(tts.spoken.any { it.startsWith("Brightness badalne ke liye permission chahiye") })
        assertTrue("Jawab hai 100." in tts.spoken)
        assertTrue("Jawab hai 18." in tts.spoken)

        val summary = recorded.single()
        val assistant = summary.screens.single { it.screenSignature == AssistantEngine.ASSISTANT_SCREEN }
        assertEquals(AssistantEngine.ASSISTANT_SCREEN, assistant.flowId)
        assertEquals(listOf("System", "Camera", "Media", "Brightness", "Calculate", "Calculate"), assistant.steps.map { it.label })
    }
}
