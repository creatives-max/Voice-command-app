package com.voicecontrol.core.engine

import com.voicecontrol.core.engine.port.ListenRequest
import com.voicecontrol.core.engine.port.ListenResult
import com.voicecontrol.core.engine.port.SessionConfig
import com.voicecontrol.core.engine.port.SpeechToText
import com.voicecontrol.core.model.ElementKind
import com.voicecontrol.core.model.FieldType
import com.voicecontrol.core.model.Language
import com.voicecontrol.core.model.ScreenElement
import com.voicecontrol.core.model.ScreenSnapshot
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OfflineSpeechTest {

    @Test
    fun `language packs are matched by language and region`() {
        assertTrue(OfflineLanguages.matches("hi-IN", "hi-IN"))
        assertTrue(OfflineLanguages.matches("hi", "hi-IN"))
        assertTrue(OfflineLanguages.matches("hi_IN", "hi-IN"))
        assertFalse(OfflineLanguages.matches("en-US", "en-IN"))
        assertFalse(OfflineLanguages.matches("mr-IN", "hi-IN"))

        val info = RecognitionSupportInfo(installed = listOf("en-IN", "hi-IN"), pending = listOf("ta-IN"), supported = listOf("mr-IN", "ta-IN", "en-IN", "hi-IN"))
        val packs = OfflineLanguages.packs(info) { if (it == Language.TAMIL) VoiceState.NEEDS_DOWNLOAD else VoiceState.INSTALLED }
        val byLanguage = packs.associateBy { it.language }
        assertEquals(SpeechPackState.INSTALLED, byLanguage.getValue(Language.HINDI).speech)
        assertEquals(SpeechPackState.INSTALLED, byLanguage.getValue(Language.HINGLISH).speech)
        assertEquals(SpeechPackState.DOWNLOADING, byLanguage.getValue(Language.TAMIL).speech)
        assertEquals(SpeechPackState.AVAILABLE, byLanguage.getValue(Language.MARATHI).speech)
        assertEquals(SpeechPackState.UNSUPPORTED, byLanguage.getValue(Language.GUJARATI).speech)
        assertTrue(byLanguage.getValue(Language.MARATHI).canDownloadSpeech)
        assertTrue(byLanguage.getValue(Language.TAMIL).canDownloadVoice)
        assertEquals("Works offline in English, हिन्दी, Hinglish.", OfflineLanguages.summary(packs))
        assertEquals(SpeechPackState.UNKNOWN, OfflineLanguages.speechState(Language.HINDI, null))
    }

    /** Fails with a network error unless asked to recognize offline. */
    private class FlakyStt(private val offlineResult: ListenResult) : SpeechToText {
        val requests = mutableListOf<ListenRequest>()
        override suspend fun listen(request: ListenRequest, onPartial: (String) -> Unit, onLevel: (Float) -> Unit): ListenResult {
            requests += request
            return if (request.preferOffline) offlineResult else ListenResult.Error("Speech service is unreachable", true, ListenResult.ErrorCause.NETWORK)
        }
        override fun cancel() = Unit
    }

    private val name = ScreenElement("vid:name", ElementKind.TEXT_FIELD, "Full name", FieldType.NAME)
    private val form = ScreenSnapshot("com.shop", elements = listOf(name), signature = "form")

    private fun TestScope.engine(screen: FakeScreen, stt: SpeechToText, tts: RecordingTts, cfg: SessionConfig) = AssistantEngine(
        screen = screen, stt = stt, tts = tts, interpreter = LocalInterpreter(), flows = { null }, profiles = { null }, recorder = { },
        config = { cfg }, scope = this, screenSettleMillis = 10,
    )

    @Test
    fun `without internet the session switches to on-device speech`() = runTest {
        val screen = FakeScreen(form)
        val stt = FlakyStt(ListenResult.Heard("Rahul Sharma"))
        engine(screen, stt, RecordingTts(), SessionConfig(confirmValues = false)).start()
        advanceUntilIdle()
        assertEquals("Rahul Sharma", screen.valueOf("vid:name"))
        assertEquals(listOf(false, true), stt.requests.map { it.preferOffline })
    }

    @Test
    fun `a missing offline pack is explained once`() = runTest {
        val tts = RecordingTts()
        val stt = FlakyStt(ListenResult.Error("Language not available", true, ListenResult.ErrorCause.LANGUAGE_UNAVAILABLE))
        engine(FakeScreen(form), stt, tts, SessionConfig(language = Language.HINDI, confirmValues = false, preferOffline = true)).start()
        advanceUntilIdle()
        assertTrue(stt.requests.all { it.preferOffline })
        assertEquals(1, tts.spoken.count { it.contains("ऑफ़लाइन के लिए डाउनलोड नहीं है") })
    }
}
