package com.voicecontrol.core.engine

import com.voicecontrol.core.engine.port.ListenRequest
import com.voicecontrol.core.engine.port.ListenResult
import com.voicecontrol.core.engine.port.SessionConfig
import com.voicecontrol.core.engine.port.SpeechDetector
import com.voicecontrol.core.engine.port.SpeechToText
import com.voicecontrol.core.engine.port.TextToSpeech
import com.voicecontrol.core.model.ElementKind
import com.voicecontrol.core.model.FieldType
import com.voicecontrol.core.model.Language
import com.voicecontrol.core.model.ScreenElement
import com.voicecontrol.core.model.ScreenSnapshot
import com.voicecontrol.core.model.UserProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Context memory, confidence re-ask, early commands from partials, barge-in and the new languages. */
class VoiceUpgradesTest {
    private val permanent = ScreenElement("vid:perm", ElementKind.TEXT_FIELD, "Permanent address", FieldType.ADDRESS)
    private val pin = ScreenElement("vid:pin", ElementKind.TEXT_FIELD, "PIN code", FieldType.PINCODE)
    private val current = ScreenElement("vid:cur", ElementKind.TEXT_FIELD, "Current address", FieldType.ADDRESS)
    private val email = ScreenElement("vid:email", ElementKind.TEXT_FIELD, "Email", FieldType.EMAIL)
    private val name = ScreenElement("vid:name", ElementKind.TEXT_FIELD, "Name", FieldType.NAME)

    /** STT whose answers carry recognizer confidence. */
    private class ConfidenceStt(vararg answers: Pair<String, Float?>) : SpeechToText {
        private val queue = ArrayDeque(answers.toList())
        override suspend fun listen(request: ListenRequest, onPartial: (String) -> Unit, onLevel: (Float) -> Unit): ListenResult {
            val (text, confidence) = queue.removeFirstOrNull() ?: return ListenResult.NoMatch
            return ListenResult.Heard(text, confidence = confidence)
        }
        override fun cancel() = Unit
    }

    private fun TestScope.engine(screen: FakeScreen, stt: SpeechToText, tts: TextToSpeech = RecordingTts(), cfg: SessionConfig = SessionConfig(confirmValues = false, askBeforeSubmit = false), profile: UserProfile? = null, detector: SpeechDetector? = null) =
        AssistantEngine(
            screen = screen, stt = stt, tts = tts, interpreter = LocalInterpreter(), flows = { null }, profiles = { profile },
            recorder = { }, config = { cfg }, scope = this, clock = { 1_000L }, newId = { "s" }, screenSettleMillis = 10, speechDetector = detector,
        )

    @Test
    fun `resolver handles same-as, labels and profile references in many languages`() {
        val memory = listOf(
            MemoryItem("Permanent address", FieldType.ADDRESS, "12 MG Road"),
            MemoryItem("PIN code", FieldType.PINCODE, "110001"),
            MemoryItem("Father name", FieldType.NAME, "Suresh"),
        )
        val profile = UserProfile(fullName = "Rahul Sharma", email = "rahul@example.com", phone = "9876543210")
        assertEquals("12 MG Road", ContextResolver.resolve("same as above", current, memory, profile))
        assertEquals("12 MG Road", ContextResolver.resolve("वही", current, memory, profile))
        assertEquals("12 MG Road", ContextResolver.resolve("அதே", current, memory, profile))
        assertEquals("110001", ContextResolver.resolve("same as pin code", current, memory, profile))
        assertEquals("Suresh", ContextResolver.resolve("same as father's name", name, memory, profile))
        assertEquals("rahul@example.com", ContextResolver.resolve("my email", email, memory, profile))
        assertEquals("rahul@example.com", ContextResolver.resolve("मेरा ईमेल", email, memory, profile))
        assertEquals("9876543210", ContextResolver.resolve("use my phone number", email, memory, profile))
        assertEquals("Rahul Sharma", ContextResolver.resolve("আমার নাম", name, memory, profile))
        assertNull(ContextResolver.resolve("mera naam Rahul hai", name, memory, profile))
        assertNull(ContextResolver.resolve("Samantha", name, memory, profile))
        assertNull(ContextResolver.resolve("same", current, emptyList(), profile))
        assertNull(ContextResolver.resolve("same", current.copy(isSensitive = true), memory, profile))
    }

    @Test
    fun `same as above fills from an earlier answer without asking the AI`() = runTest {
        val screen = FakeScreen(ScreenSnapshot("com.form", elements = listOf(permanent, current), signature = "s"))
        engine(screen, ScriptedStt("12 MG Road", "same as above")).start()
        advanceUntilIdle()
        assertEquals("12 MG Road", screen.valueOf("vid:cur"))
    }

    @Test
    fun `low recognizer confidence asks did you say before typing`() = runTest {
        val screen = FakeScreen(ScreenSnapshot("com.form", elements = listOf(name), signature = "s"))
        val tts = RecordingTts()
        engine(screen, ConfidenceStt("Raul" to 0.3f, "no" to 0.9f, "Rahul" to 0.95f), tts).start()
        advanceUntilIdle()
        assertEquals("Rahul", screen.valueOf("vid:name"))
        assertTrue(tts.spoken.contains("Did you say Raul?"))
        assertEquals(1, tts.spoken.count { it.startsWith("Did you say") })
    }

    @Test
    fun `a stable command in the partial transcript ends listening early`() = runTest {
        var stopped = false
        val stt = object : SpeechToText {
            override suspend fun listen(request: ListenRequest, onPartial: (String) -> Unit, onLevel: (Float) -> Unit): ListenResult {
                onPartial("stop")
                val done = CompletableDeferred<ListenResult>()
                stopFn = { stopped = true; done.complete(ListenResult.NoMatch) }
                return done.await()
            }
            var stopFn: () -> Unit = {}
            override fun cancel() = Unit
            override fun stopListening() = stopFn()
        }
        val screen = FakeScreen(ScreenSnapshot("com.form", elements = listOf(name), signature = "s"))
        val tts = RecordingTts()
        engine(screen, stt, tts).start()
        advanceUntilIdle()
        assertTrue(stopped)
        assertTrue(tts.spoken.contains("Stopped."))
    }

    @Test
    fun `barge-in stops the question when the user speaks`() = runTest {
        var ttsStopped = 0
        val slowTts = object : TextToSpeech {
            val spoken = mutableListOf<String>()
            override suspend fun speak(text: String, languageTag: String, rate: Float): Boolean {
                spoken += text
                if (text.startsWith("Please say")) awaitCancellation()
                return true
            }
            override fun stop() { ttsStopped++ }
        }
        val screen = FakeScreen(ScreenSnapshot("com.form", elements = listOf(name), signature = "s"))
        val cfg = SessionConfig(confirmValues = false, askBeforeSubmit = false, bargeIn = true)
        engine(screen, ScriptedStt("Meera"), slowTts, cfg, detector = { }).start()
        advanceUntilIdle()
        assertEquals("Meera", screen.valueOf("vid:name"))
        assertTrue(ttsStopped >= 1)
    }

    @Test
    fun `new languages speak their own phrases`() = runTest {
        val expected = mapOf(
            Language.MARATHI to "इथे तुमचे Name भरायचे आहे. मी एक-एक करून विचारतो.",
            Language.TAMIL to "இங்கே உங்கள் Name நிரப்ப வேண்டும். ஒவ்வொன்றாகக் கேட்கிறேன்.",
            Language.TELUGU to "ఇక్కడ మీ Name నింపాలి. ఒక్కొక్కటిగా అడుగుతాను.",
            Language.BENGALI to "এখানে আপনার Name পূরণ করতে হবে। একে একে জিজ্ঞেস করছি।",
            Language.GUJARATI to "અહીં તમારું Name ભરવાનું છે. હું એક-એક કરીને પૂછું છું.",
        )
        expected.forEach { (language, phrase) ->
            val screen = FakeScreen(ScreenSnapshot("com.form", elements = listOf(name), signature = "s"))
            val tts = RecordingTts()
            val stt = ScriptedStt("রাহুল")
            engine(screen, stt, tts, SessionConfig(language = language, confirmValues = false, askBeforeSubmit = false)).start()
            advanceUntilIdle()
            assertEquals(phrase, tts.spoken.first(), language.name)
            assertEquals(language.speechTag, stt.requests.first().languageTag)
            assertEquals("Rahul", screen.valueOf("vid:name"), language.name)
        }
        Language.entries.forEach { l -> assertTrue(Phrases(l).help().isNotBlank()) }
    }
}
