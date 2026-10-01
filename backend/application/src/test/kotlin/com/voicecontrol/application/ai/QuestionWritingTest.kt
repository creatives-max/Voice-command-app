package com.voicecontrol.application.ai

import com.voicecontrol.domain.ai.ElementKind
import com.voicecontrol.domain.ai.FieldQuestion
import com.voicecontrol.domain.ai.FieldType
import com.voicecontrol.domain.ai.InterpretCommand
import com.voicecontrol.domain.ai.Interpretation
import com.voicecontrol.domain.ai.Language
import com.voicecontrol.domain.ai.LlmProvider
import com.voicecontrol.domain.ai.QuestionsCommand
import com.voicecontrol.domain.ai.ScreenContext
import com.voicecontrol.domain.ai.ScreenElement
import com.voicecontrol.domain.ai.VisionCommand
import com.voicecontrol.domain.ai.VisionResult
import com.voicecontrol.domain.event.Cache
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuestionWritingTest {
    private val screen = ScreenContext(
        "com.bank",
        elements = listOf(
            ScreenElement("name", ElementKind.TEXT_FIELD, "Full name", FieldType.NAME, value = "Asha"),
            ScreenElement("mobile", ElementKind.TEXT_FIELD, "Mobile", FieldType.PHONE),
            ScreenElement("pwd", ElementKind.TEXT_FIELD, "Password", FieldType.PASSWORD, isSensitive = true),
            ScreenElement("terms", ElementKind.CHECKBOX, "I agree"),
            ScreenElement("go", ElementKind.BUTTON, "Continue"),
        ),
    )

    private class Writer(private val answer: suspend (QuestionsCommand) -> List<FieldQuestion>) : LlmProvider {
        var calls = 0
        var last: QuestionsCommand? = null
        override val name = "fake"
        override suspend fun interpret(command: InterpretCommand): Interpretation = error("not used")
        override suspend fun detectElements(command: VisionCommand): VisionResult = throw UnsupportedOperationException()
        override suspend fun writeQuestions(command: QuestionsCommand): List<FieldQuestion> {
            calls++
            last = command
            return answer(command)
        }
    }

    private class MapCache : Cache {
        val data = java.util.concurrent.ConcurrentHashMap<String, String>()
        override suspend fun get(key: String) = data[key]
        override suspend fun put(key: String, value: String, ttlSeconds: Long) { data[key] = value }
        override suspend fun delete(vararg keys: String) { keys.forEach(data::remove) }
        override suspend fun deleteByPrefix(prefix: String) { data.keys.removeAll { it.startsWith(prefix) } }
    }

    @Test
    fun `sends only fields without values, keeps known ids and caches per screen and language`() = runBlocking {
        val writer = Writer {
            listOf(
                FieldQuestion("name", "  Aapka poora naam kya hai?  ", "Jaise: Ravi Kumar."),
                FieldQuestion("mobile", "Aapka 10 ank ka mobile number boliye.", ""),
                FieldQuestion("pwd", "Password?", null),
                FieldQuestion("ghost", "Made up?", null),
                FieldQuestion("name", "Duplicate", null),
            )
        }
        val cache = MapCache()
        val ai = AiService(writer, cache = cache)
        val result = ai.writeQuestions(QuestionsCommand(screen, Language.HINGLISH))

        // The model never sees buttons, sensitive fields or any typed value.
        assertEquals(listOf("name", "mobile", "terms"), writer.last!!.screen.elements.map { it.id })
        assertTrue(writer.last!!.screen.elements.all { it.value == null })
        assertEquals(
            listOf(FieldQuestion("name", "Aapka poora naam kya hai?", "Jaise: Ravi Kumar."), FieldQuestion("mobile", "Aapka 10 ank ka mobile number boliye.", null)),
            result.questions,
        )

        ai.writeQuestions(QuestionsCommand(screen, Language.HINGLISH))
        assertEquals(1, writer.calls)
        ai.writeQuestions(QuestionsCommand(screen, Language.TAMIL))
        assertEquals(2, writer.calls)
        // Typed values don't change the key.
        ai.writeQuestions(QuestionsCommand(screen.copy(elements = screen.elements.map { it.copy(value = "x") }), Language.HINGLISH))
        assertEquals(2, writer.calls)
    }

    @Test
    fun `failures, timeouts and the rules provider give no questions`() = runBlocking {
        assertTrue(AiService(Writer { error("boom") }).writeQuestions(QuestionsCommand(screen)).questions.isEmpty())
        assertTrue(AiService(Writer { delay(60_000); emptyList() }, timeoutMillis = 50).writeQuestions(QuestionsCommand(screen)).questions.isEmpty())
        assertTrue(AiService(RulesProvider()).writeQuestions(QuestionsCommand(screen)).questions.isEmpty())
        val onlyButtons = screen.copy(elements = listOf(ScreenElement("go", ElementKind.BUTTON, "Continue")))
        val writer = Writer { emptyList() }
        AiService(writer).writeQuestions(QuestionsCommand(onlyButtons))
        assertEquals(0, writer.calls)
    }

    @Test
    fun `slow writing finishes in the background and is served from the cache next time`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val writer = Writer {
            gate.await()
            listOf(FieldQuestion("mobile", "Mobile number?", null))
        }
        val cache = MapCache()
        val ai = AiService(writer, timeoutMillis = 20, cache = cache)
        // The phone gives up; the same screen asked again meanwhile shares the one model call.
        assertTrue(ai.writeQuestions(QuestionsCommand(screen)).questions.isEmpty())
        assertTrue(ai.writeQuestions(QuestionsCommand(screen)).questions.isEmpty())
        gate.complete(Unit)
        withTimeout(5_000) { while (cache.data.isEmpty()) delay(10) }
        assertEquals("Mobile number?", ai.writeQuestions(QuestionsCommand(screen)).questions.single().question)
        assertEquals(1, writer.calls)
    }

    @Test
    fun `parses the model's JSON`() {
        val parsed = ModelOutput.questions("""{"questions":[{"elementId":"name","question":"Naam?","hint":""},{"elementId":"","question":"x","hint":""}]}""")
        assertEquals(listOf(FieldQuestion("name", "Naam?", null)), parsed)
        assertNull(ModelOutput.questions("""{"questions":[]}""").firstOrNull())
    }
}
