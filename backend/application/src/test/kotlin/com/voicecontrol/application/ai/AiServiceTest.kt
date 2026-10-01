package com.voicecontrol.application.ai

import com.voicecontrol.domain.ai.ElementKind
import com.voicecontrol.domain.ai.FieldFill
import com.voicecontrol.domain.ai.FieldType
import com.voicecontrol.domain.ai.IntentKind
import com.voicecontrol.domain.ai.InterpretCommand
import com.voicecontrol.domain.ai.Interpretation
import com.voicecontrol.domain.ai.Language
import com.voicecontrol.domain.ai.LlmProvider
import com.voicecontrol.domain.ai.ScreenContext
import com.voicecontrol.domain.ai.ScreenElement
import com.voicecontrol.domain.ai.VisionCommand
import com.voicecontrol.domain.ai.VisionResult
import com.voicecontrol.domain.common.DomainException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiServiceTest {
    private val screen = ScreenContext(
        "com.shop",
        elements = listOf(
            ScreenElement("name", ElementKind.TEXT_FIELD, "Name", FieldType.NAME),
            ScreenElement("phone", ElementKind.TEXT_FIELD, "Mobile", FieldType.PHONE),
            ScreenElement("pwd", ElementKind.TEXT_FIELD, "Password", FieldType.PASSWORD, value = "leak", isSensitive = true),
            ScreenElement("go", ElementKind.BUTTON, "Continue"),
        ),
    )

    private class FakeProvider(private val answer: suspend (InterpretCommand) -> Interpretation) : LlmProvider {
        var calls = 0
        var lastCommand: InterpretCommand? = null
        override val name = "fake"
        override suspend fun interpret(command: InterpretCommand): Interpretation {
            calls++
            lastCommand = command
            return answer(command)
        }
        override suspend fun detectElements(command: VisionCommand): VisionResult = throw UnsupportedOperationException()
    }

    private fun cmd(utterance: String, field: String? = "name", language: Language = Language.ENGLISH) =
        InterpretCommand(screen, field, utterance, language)

    @Test
    fun `commands never reach the llm`() = runTest {
        val provider = FakeProvider { Interpretation(IntentKind.UNKNOWN) }
        val service = AiService(provider)
        assertEquals(IntentKind.NEXT, service.interpret(cmd("aage badho")).intent)
        assertEquals(IntentKind.CLICK, service.interpret(cmd("continue dabao")).intent)
        assertEquals(0, provider.calls)
    }

    @Test
    fun `sensitive values are redacted before the provider sees them`() = runTest {
        val provider = FakeProvider { Interpretation(IntentKind.FILL, "name", "Rahul", source = "fake") }
        AiService(provider).interpret(cmd("my name is rahul"))
        assertNull(provider.lastCommand!!.screen.element("pwd")!!.value)
    }

    @Test
    fun `sanitizer drops sensitive and unknown targets`() = runTest {
        val provider = FakeProvider {
            Interpretation(IntentKind.FILL, "name", "Rahul", extraFills = listOf(FieldFill("pwd", "x"), FieldFill("ghost", "y"), FieldFill("phone", "9876543210")), source = "fake")
        }
        val result = AiService(provider).interpret(cmd("rahul and phone 9876543210"))
        assertEquals(listOf(FieldFill("phone", "9876543210")), result.extraFills)

        val toPassword = AiService(FakeProvider { Interpretation(IntentKind.FILL, "pwd", "secret", source = "fake") }).interpret(cmd("x y z", field = "phone"))
        assertEquals("phone", toPassword.targetId)
    }

    @Test
    fun `provider failures and timeouts fall back to rules`() = runTest {
        val failing = AiService(FakeProvider { throw IllegalStateException("boom") })
        val r1 = failing.interpret(cmd("nine eight seven six five four three two one zero", field = "phone"))
        assertEquals("9876543210", r1.value)
        assertEquals("rules-fallback", r1.source)

        val slow = AiService(FakeProvider { delay(10_000); Interpretation(IntentKind.UNKNOWN) }, timeoutMillis = 50)
        assertEquals("rules-fallback", slow.interpret(cmd("rahul")).source)
    }

    @Test
    fun `rules provider handles hinglish answers`() = runTest {
        val r = AiService(RulesProvider()).interpret(cmd("mera naam rahul sharma hai", language = Language.HINGLISH))
        assertEquals(IntentKind.FILL, r.intent)
        assertEquals("Rahul Sharma", r.value)
    }

    @Test
    fun `invalid requests are rejected`() = runTest {
        assertFailsWith<DomainException.Validation> { AiService(RulesProvider()).interpret(cmd("   ")) }
    }

    @Test
    fun `session memory reaches the model without secrets, in any supported language`() = runTest {
        val provider = FakeProvider { Interpretation(IntentKind.FILL, "name", "Suresh") }
        val service = AiService(provider)
        val memory = listOf(
            com.voicecontrol.domain.ai.MemoryItem("Father name", "Suresh"),
            com.voicecontrol.domain.ai.MemoryItem("Password", "hunter2"),
            com.voicecontrol.domain.ai.MemoryItem("Card number", "4111"),
        )
        service.interpret(InterpretCommand(screen, "name", "அதே பெயர்", Language.TAMIL, memory = memory))
        assertEquals(listOf("Father name"), provider.lastCommand!!.memory.map { it.label })
        val prompt = Prompts.interpretUserMessage(provider.lastCommand!!)
        assertTrue("Suresh" in prompt && "hunter2" !in prompt && "TAMIL" in prompt)
    }
}
