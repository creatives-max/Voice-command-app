package com.voicecontrol.core.data.ai

import com.voicecontrol.core.data.auth.TokenStore
import com.voicecontrol.core.data.settings.SettingsRepository
import com.voicecontrol.core.engine.port.AgentAction
import com.voicecontrol.core.engine.port.AgentDecision
import com.voicecontrol.core.engine.port.GoalAgent
import com.voicecontrol.core.model.Language
import com.voicecontrol.core.model.ScreenSnapshot
import com.voicecontrol.core.network.AiApi
import com.voicecontrol.core.network.dto.AgentStepRequestDto
import com.voicecontrol.core.network.dto.ScreenContextDto
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plans "do it for me" steps with the backend's AI. Unavailable (null) on device-only mode, when signed
 * out, offline, or when the server has no planning model (it answers GIVE_UP without a model source).
 */
@Singleton
class RemoteGoalAgent @Inject constructor(
    private val ai: AiApi,
    private val settings: SettingsRepository,
    private val tokens: TokenStore,
) : GoalAgent {
    override suspend fun next(goal: String, screen: ScreenSnapshot, history: List<String>, language: Language): AgentDecision? {
        if (settings.appSettings().localOnly || tokens.tokens() == null) return null
        val step = runCatching {
            ai.agentStep(AgentStepRequestDto(goal.take(MAX_GOAL), ScreenContextDto.from(screen.redacted()), history, language, screen.texts.map { it.text }))
        }.getOrNull() ?: return null
        val action = AgentAction.entries.firstOrNull { it.name == step.action } ?: return null
        // No model behind the server (or it failed): let the phone carry on by itself.
        if (action == AgentAction.GIVE_UP && step.source in NO_PLANNER) return null
        return AgentDecision(action, step.targetId, step.value, step.say, step.question, step.appName, step.confirm, step.plan)
    }

    private companion object {
        const val MAX_GOAL = 500
        val NO_PLANNER = setOf("rules", "error", "timeout")
    }
}
