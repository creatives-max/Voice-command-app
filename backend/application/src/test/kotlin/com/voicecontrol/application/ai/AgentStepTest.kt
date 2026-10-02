package com.voicecontrol.application.ai

import com.voicecontrol.domain.ai.AgentActionKind
import com.voicecontrol.domain.ai.AgentStep
import com.voicecontrol.domain.ai.AgentStepCommand
import com.voicecontrol.domain.ai.ElementKind
import com.voicecontrol.domain.ai.FieldType
import com.voicecontrol.domain.ai.InterpretCommand
import com.voicecontrol.domain.ai.Interpretation
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

class AgentStepTest {
    private val screen = ScreenContext(
        "com.power",
        elements = listOf(
            ScreenElement("pay", ElementKind.BUTTON, "Pay bill"),
            ScreenElement("consumer", ElementKind.TEXT_FIELD, "Consumer number", FieldType.NUMBER),
            ScreenElement("pin", ElementKind.TEXT_FIELD, "UPI PIN", FieldType.PIN, value = "1234", isSensitive = true),
        ),
    )

    private class Planner(private val answer: suspend (AgentStepCommand) -> AgentStep) : LlmProvider {
        var last: AgentStepCommand? = null
        override val name = "fake"
        override suspend fun interpret(command: InterpretCommand): Interpretation = error("not used")
        override suspend fun detectElements(command: VisionCommand): VisionResult = throw UnsupportedOperationException()
        override suspend fun nextAgentStep(command: AgentStepCommand): AgentStep {
            last = command
            return answer(command)
        }
    }

    private fun cmd(history: List<String> = emptyList()) = AgentStepCommand("bijli ka bill bharna hai", screen, history)

    @Test
    fun `passes the goal and history, never sensitive values, and keeps valid steps`() = runTest {
        val planner = Planner { AgentStep(AgentActionKind.CLICK, targetId = "pay", say = "  Bill payment khol raha hoon. ", confirm = false, source = "fake") }
        val step = AiService(planner).nextAgentStep(cmd(List(40) { "step $it" }))
        assertEquals(AgentActionKind.CLICK, step.action)
        assertEquals("Bill payment khol raha hoon.", step.say)
        assertNull(planner.last!!.screen.elements.first { it.id == "pin" }.value)
        assertEquals(30, planner.last!!.history.size)
        assertEquals("step 39", planner.last!!.history.last())
    }

    @Test
    fun `screen texts are capped and masked again on the server`() = runTest {
        val planner = Planner { AgentStep(AgentActionKind.DONE) }
        AiService(planner).nextAgentStep(cmd().copy(texts = listOf("Bill amount ₹540", "A/c 123456789012", "OTP 4821") + List(100) { "line $it" }))
        val sent = planner.last!!.texts
        assertEquals(80, sent.size)
        assertEquals(listOf("Bill amount ₹540", "A/c ••••••••9012", "OTP ••••"), sent.take(3))
    }

    @Test
    fun `unknown ids give up and sensitive or empty fills become questions`() = runTest {
        assertEquals(AgentActionKind.GIVE_UP, AiService(Planner { AgentStep(AgentActionKind.CLICK, targetId = "ghost") }).nextAgentStep(cmd()).action)
        val pin = AiService(Planner { AgentStep(AgentActionKind.FILL, targetId = "pin", value = "0000") }).nextAgentStep(cmd())
        assertEquals(AgentActionKind.ASK, pin.action)
        assertEquals("pin", pin.targetId)
        assertNull(pin.value)
        assertEquals(AgentActionKind.ASK, AiService(Planner { AgentStep(AgentActionKind.FILL, targetId = "consumer", value = "") }).nextAgentStep(cmd()).action)
        val fill = AiService(Planner { AgentStep(AgentActionKind.FILL, targetId = "consumer", value = "1234567") }).nextAgentStep(cmd())
        assertEquals("1234567", fill.value)
        assertEquals(AgentActionKind.GIVE_UP, AiService(Planner { AgentStep(AgentActionKind.OPEN_APP) }).nextAgentStep(cmd()).action)
    }

    @Test
    fun `no planner, failures and timeouts give up, bad input is refused`() = runTest {
        assertEquals(AgentActionKind.GIVE_UP, AiService(RulesProvider()).nextAgentStep(cmd()).action)
        assertEquals("error", AiService(Planner { error("boom") }).nextAgentStep(cmd()).source)
        assertEquals("timeout", AiService(Planner { delay(60_000); AgentStep(AgentActionKind.DONE) }, timeoutMillis = 20).nextAgentStep(cmd()).source)
        assertFailsWith<DomainException.Validation> { AiService(RulesProvider()).nextAgentStep(cmd().copy(goal = " ")) }
    }

    @Test
    fun `parses the model's JSON`() {
        val step = ModelOutput.agentStep("""{"action":"ASK","targetId":"consumer","value":"","say":"","question":"Consumer number boliye?","appName":"","confirm":false}""", "anthropic")
        assertEquals(AgentStep(AgentActionKind.ASK, targetId = "consumer", question = "Consumer number boliye?", source = "anthropic"), step)
        assertTrue(ModelOutput.agentStep("""{"action":"CLICK","targetId":"pay","confirm":true}""", "x").confirm)
        assertEquals(AgentActionKind.GIVE_UP, ModelOutput.agentStep("""{"action":"DANCE"}""", "x").action)
    }
}
