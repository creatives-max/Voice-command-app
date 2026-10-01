package com.voicecontrol.application.ai

import com.voicecontrol.domain.ai.InterpretCommand
import com.voicecontrol.domain.ai.Interpretation
import com.voicecontrol.domain.ai.LlmProvider
import com.voicecontrol.domain.ai.VisionCommand
import com.voicecontrol.domain.ai.VisionResult

/** `LLM_PROVIDER=rules`: no external model; deterministic Hindi/English/Hinglish rules. */
class RulesProvider(private val rules: RulesInterpreter = RulesInterpreter()) : LlmProvider {
    override val name: String = RulesInterpreter.SOURCE
    override suspend fun interpret(command: InterpretCommand): Interpretation = rules.interpret(command)
    override suspend fun detectElements(command: VisionCommand): VisionResult =
        throw UnsupportedOperationException("rules provider has no vision")
}
