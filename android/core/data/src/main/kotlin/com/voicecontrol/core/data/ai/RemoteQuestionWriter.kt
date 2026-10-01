package com.voicecontrol.core.data.ai

import com.voicecontrol.core.data.auth.TokenStore
import com.voicecontrol.core.data.settings.SettingsRepository
import com.voicecontrol.core.engine.port.QuestionWriter
import com.voicecontrol.core.engine.port.WrittenQuestion
import com.voicecontrol.core.model.Language
import com.voicecontrol.core.model.ScreenSnapshot
import com.voicecontrol.core.network.AiApi
import com.voicecontrol.core.network.dto.QuestionsRequestDto
import com.voicecontrol.core.network.dto.ScreenContextDto
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Friendly questions written by the backend's AI (when signed in and not on-device only). Any failure
 * returns no questions, so the built-in ones are used.
 */
@Singleton
class RemoteQuestionWriter @Inject constructor(
    private val ai: AiApi,
    private val settings: SettingsRepository,
    private val tokens: TokenStore,
) : QuestionWriter {
    override suspend fun write(screen: ScreenSnapshot, language: Language): Map<String, WrittenQuestion> {
        if (settings.appSettings().localOnly || tokens.tokens() == null) return emptyMap()
        val response = runCatching { ai.questions(QuestionsRequestDto(ScreenContextDto.from(screen), language)) }.getOrNull()
            ?: return emptyMap()
        return response.questions.associate { it.elementId to WrittenQuestion(it.question, it.hint) }
    }
}
