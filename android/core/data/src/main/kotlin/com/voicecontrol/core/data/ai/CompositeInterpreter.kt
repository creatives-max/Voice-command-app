package com.voicecontrol.core.data.ai

import com.voicecontrol.core.data.auth.TokenStore
import com.voicecontrol.core.data.settings.SettingsRepository
import com.voicecontrol.core.engine.LocalInterpreter
import com.voicecontrol.core.engine.port.InterpretRequest
import com.voicecontrol.core.engine.port.Interpreter
import com.voicecontrol.core.model.Interpretation
import com.voicecontrol.core.network.AiApi
import com.voicecontrol.core.network.dto.InterpretRequestDto
import com.voicecontrol.core.network.dto.MemoryDto
import com.voicecontrol.core.network.dto.ScreenContextDto
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The "AI brain" used by the engine: sends screen + speech to the backend (which calls the LLM)
 * when signed in and not in local-only mode; falls back to on-device rules on any failure or timeout.
 */
@Singleton
class CompositeInterpreter @Inject constructor(
    private val ai: AiApi,
    private val settings: SettingsRepository,
    private val tokens: TokenStore,
) : Interpreter {

    private val local = LocalInterpreter()

    override suspend fun interpret(request: InterpretRequest): Interpretation {
        local.commandOf(request)?.let { return it }
        if (settings.appSettings().localOnly || tokens.tokens() == null) return local.interpret(request)
        val dto = InterpretRequestDto(
            screen = ScreenContextDto.from(request.screen),
            currentFieldId = request.currentFieldId,
            utterance = request.utterance,
            language = request.language,
            question = request.question,
            transliterate = request.transliterate,
            memory = request.memory.map { MemoryDto(it.label, it.value) },
        )
        val remote = withTimeoutOrNull(REMOTE_TIMEOUT_MS) { runCatching { ai.interpret(dto) }.getOrNull() }
        return remote ?: local.interpret(request)
    }

    private companion object {
        const val REMOTE_TIMEOUT_MS = 9_000L
    }
}
