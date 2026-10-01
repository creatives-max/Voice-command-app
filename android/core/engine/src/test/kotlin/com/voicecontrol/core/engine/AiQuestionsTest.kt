package com.voicecontrol.core.engine

import com.voicecontrol.core.engine.port.QuestionWriter
import com.voicecontrol.core.engine.port.SessionConfig
import com.voicecontrol.core.engine.port.WrittenQuestion
import com.voicecontrol.core.model.ElementKind
import com.voicecontrol.core.model.FieldType
import com.voicecontrol.core.model.Language
import com.voicecontrol.core.model.ScreenElement
import com.voicecontrol.core.model.ScreenSnapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AI-written questions and hints replace the built-in ones for fields without a saved question. */
class AiQuestionsTest {
    private val mobile = ScreenElement("vid:mobile", ElementKind.TEXT_FIELD, "Mobile", FieldType.PHONE)
    private val pin = ScreenElement("vid:pin", ElementKind.TEXT_FIELD, "PIN", FieldType.PIN, value = "1234", isSensitive = true)
    private val form = ScreenSnapshot("com.bank", elements = listOf(mobile, pin), signature = "signup")

    private val question = "Aapka 10 ank ka mobile number boliye."
    private val hint = "Jaise: 98765 43210."

    private class CountingWriter(private val delayMs: Long = 0, private val answer: Map<String, WrittenQuestion>) : QuestionWriter {
        var calls = 0
        var lastScreen: ScreenSnapshot? = null
        var lastLanguage: Language? = null
        override suspend fun write(screen: ScreenSnapshot, language: Language): Map<String, WrittenQuestion> {
            calls++
            lastScreen = screen
            lastLanguage = language
            if (delayMs > 0) delay(delayMs)
            return answer
        }
    }

    private fun TestScope.engine(
        screen: FakeScreen,
        stt: ScriptedStt,
        tts: RecordingTts,
        writer: QuestionWriter,
        config: SessionConfig = SessionConfig(language = Language.HINGLISH, confirmValues = false),
    ) = AssistantEngine(
        screen = screen, stt = stt, tts = tts, interpreter = LocalInterpreter(), flows = { null }, profiles = { null },
        recorder = { }, config = { config }, scope = this, screenSettleMillis = 10, questionWriter = writer,
    )

    @Test
    fun `asks the AI question and explains with its hint when the answer doesn't fit`() = runTest {
        val writer = CountingWriter(answer = mapOf("vid:mobile" to WrittenQuestion(question, hint), "vid:pin" to WrittenQuestion("PIN?")))
        val screen = FakeScreen(form)
        val tts = RecordingTts()
        engine(screen, ScriptedStt("12", "9876543210"), tts, writer).start()
        advanceUntilIdle()

        assertTrue(tts.spoken.any { it == question })
        assertTrue(tts.spoken.any { it.endsWith(hint) && it != hint }, "the hint follows the explanation of what was wrong")
        assertEquals("9876543210", screen.valueOf("vid:mobile"))
        // The writer gets the screen without the PIN's value, in the session's language.
        assertNull(writer.lastScreen!!.elements.first { it.id == "vid:pin" }.value)
        assertEquals(Language.HINGLISH, writer.lastLanguage)
        // Sensitive fields keep their built-in handling.
        assertFalse(tts.spoken.any { it == "PIN?" })
    }

    @Test
    fun `on-device only, slow or empty answers keep the built-in questions`() = runTest {
        val offline = CountingWriter(answer = mapOf("vid:mobile" to WrittenQuestion(question, hint)))
        val tts = RecordingTts()
        engine(FakeScreen(form), ScriptedStt("9876543210"), tts, offline, SessionConfig(localOnly = true, confirmValues = false)).start()
        advanceUntilIdle()
        assertEquals(0, offline.calls)
        assertFalse(tts.spoken.any { it == question })

        val slow = CountingWriter(delayMs = 60_000, answer = mapOf("vid:mobile" to WrittenQuestion(question, hint)))
        val slowTts = RecordingTts()
        val slowScreen = FakeScreen(form)
        engine(slowScreen, ScriptedStt("9876543210"), slowTts, slow).start()
        advanceUntilIdle()
        assertFalse(slowTts.spoken.any { it == question })
        assertEquals("9876543210", slowScreen.valueOf("vid:mobile"))
    }

    @Test
    fun `a screen is asked about once per language`() = runTest {
        val writer = CountingWriter(answer = mapOf("vid:mobile" to WrittenQuestion(question, hint)))
        val screen = FakeScreen(form)
        val engine = engine(screen, ScriptedStt("9876543210", "9876543210"), RecordingTts(), writer)
        engine.start()
        advanceUntilIdle()
        engine.start()
        advanceUntilIdle()
        assertEquals(1, writer.calls)
    }
}
