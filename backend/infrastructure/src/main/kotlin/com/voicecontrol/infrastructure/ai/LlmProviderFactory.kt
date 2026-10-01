package com.voicecontrol.infrastructure.ai

import com.voicecontrol.application.ai.RulesProvider
import com.voicecontrol.domain.ai.LlmProvider
import com.voicecontrol.infrastructure.config.AppConfig
import io.ktor.client.HttpClient
import org.slf4j.LoggerFactory

/** Chooses the LLM provider from `LLM_PROVIDER`; misconfiguration degrades to offline rules with a warning. */
object LlmProviderFactory {
    private val log = LoggerFactory.getLogger(LlmProviderFactory::class.java)

    fun create(config: AppConfig.LlmConfig, http: HttpClient): LlmProvider = when (config.provider) {
        "anthropic" -> config.anthropicApiKey?.let { AnthropicProvider(it, config.anthropicModel) } ?: rules("ANTHROPIC_API_KEY is not set")
        "openai" -> config.openAiApiKey?.let { OpenAiProvider(http, it, config.openAiModel, config.openAiBaseUrl) } ?: rules("OPENAI_API_KEY is not set")
        "rules" -> RulesProvider()
        else -> rules("unknown LLM_PROVIDER '${config.provider}'")
    }

    private fun rules(reason: String): LlmProvider {
        log.warn("Using offline rules provider: {}", reason)
        return RulesProvider()
    }
}
