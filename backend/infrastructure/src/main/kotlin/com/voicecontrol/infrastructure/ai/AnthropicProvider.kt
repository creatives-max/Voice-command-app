package com.voicecontrol.infrastructure.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.TextBlockParam
import com.voicecontrol.application.ai.ModelOutput
import com.voicecontrol.application.ai.Prompts
import com.voicecontrol.domain.ai.FieldQuestion
import com.voicecontrol.domain.ai.InterpretCommand
import com.voicecontrol.domain.ai.QuestionsCommand
import com.voicecontrol.domain.ai.Interpretation
import com.voicecontrol.domain.ai.LlmProvider
import com.voicecontrol.domain.ai.VisionCommand
import com.voicecontrol.domain.ai.VisionResult
import com.voicecontrol.domain.common.DomainException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.util.Base64

/**
 * `LLM_PROVIDER=anthropic` — Claude via the official Anthropic Java SDK with structured JSON output.
 * Interpretation is latency-sensitive (the user is waiting mid-conversation), so it runs at low effort.
 */
class AnthropicProvider(
    private val client: AnthropicClient,
    private val model: String,
) : LlmProvider {

    constructor(apiKey: String, model: String) : this(AnthropicOkHttpClient.builder().apiKey(apiKey).build(), model)

    override val name: String = "anthropic"

    override suspend fun interpret(command: InterpretCommand): Interpretation = withContext(Dispatchers.IO) {
        val params = base(Prompts.INTERPRET_SCHEMA, OutputConfig.Effort.LOW, maxTokens = 4_096)
            .system(Prompts.INTERPRET_SYSTEM)
            .addUserMessage(Prompts.interpretUserMessage(command))
            .build()
        ModelOutput.interpretation(textOf(client.messages().create(params)), name)
    }

    override suspend fun writeQuestions(command: QuestionsCommand): List<FieldQuestion> = withContext(Dispatchers.IO) {
        val params = base(Prompts.QUESTIONS_SCHEMA, OutputConfig.Effort.LOW, maxTokens = 8_000)
            .system(Prompts.QUESTIONS_SYSTEM)
            .addUserMessage(Prompts.questionsUserMessage(command))
            .build()
        ModelOutput.questions(textOf(client.messages().create(params)))
    }

    override suspend fun nextAgentStep(command: com.voicecontrol.domain.ai.AgentStepCommand): com.voicecontrol.domain.ai.AgentStep = withContext(Dispatchers.IO) {
        val params = base(Prompts.AGENT_SCHEMA, OutputConfig.Effort.LOW, maxTokens = 4_096)
            .system(Prompts.AGENT_SYSTEM)
            .addUserMessage(Prompts.agentUserMessage(command))
            .build()
        ModelOutput.agentStep(textOf(client.messages().create(params)), name)
    }

    override suspend fun detectElements(command: VisionCommand): VisionResult = withContext(Dispatchers.IO) {
        val image = ImageBlockParam.builder()
            .source(
                Base64ImageSource.builder()
                    .data(Base64.getEncoder().encodeToString(command.jpeg))
                    .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG)
                    .build(),
            )
            .build()
        val params = base(Prompts.VISION_SCHEMA, OutputConfig.Effort.MEDIUM, maxTokens = 16_000)
            .system(Prompts.VISION_SYSTEM)
            .addUserMessageOfBlockParams(
                listOf(
                    ContentBlockParam.ofImage(image),
                    ContentBlockParam.ofText(TextBlockParam.builder().text(Prompts.visionUserText(command.language)).build()),
                ),
            )
            .build()
        ModelOutput.vision(textOf(client.messages().create(params)), name)
    }

    private fun base(schema: JsonObject, effort: OutputConfig.Effort, maxTokens: Long): MessageCreateParams.Builder {
        val schemaBuilder = JsonOutputFormat.Schema.builder()
        schema.forEach { (key, value) -> schemaBuilder.putAdditionalProperty(key, JsonValue.from(value.toPlain())) }
        return MessageCreateParams.builder()
            .model(model)
            .maxTokens(maxTokens)
            .outputConfig(
                OutputConfig.builder()
                    .effort(effort)
                    .format(JsonOutputFormat.builder().schema(schemaBuilder.build()).build())
                    .build(),
            )
            // Server-side refusal fallback: if a safety classifier declines, the API retries on a fallback model.
            .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
    }

    private fun textOf(message: Message): String {
        val stop = message.stopReason().map { it.toString() }.orElse("")
        if (stop == "refusal") throw DomainException.Upstream("The model declined this request")
        if (stop == "max_tokens") throw DomainException.Upstream("The model response was truncated")
        return message.content().mapNotNull { block -> block.text().orElse(null)?.text() }.joinToString("")
    }
}
