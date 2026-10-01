package com.voicecontrol.infrastructure.ai

import com.voicecontrol.application.ai.ModelOutput
import com.voicecontrol.application.ai.Prompts
import com.voicecontrol.domain.ai.InterpretCommand
import com.voicecontrol.domain.ai.Interpretation
import com.voicecontrol.domain.ai.LlmProvider
import com.voicecontrol.domain.ai.VisionCommand
import com.voicecontrol.domain.ai.VisionResult
import com.voicecontrol.domain.common.DomainException
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Base64

/**
 * `LLM_PROVIDER=openai` — any OpenAI-compatible Chat Completions endpoint (OpenAI, Azure OpenAI,
 * vLLM, Ollama, …) selected with `OPENAI_BASE_URL`, using JSON-schema structured output.
 */
class OpenAiProvider(
    private val http: HttpClient,
    private val apiKey: String,
    private val model: String,
    private val baseUrl: String = "https://api.openai.com/v1",
) : LlmProvider {

    override val name: String = "openai"
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun interpret(command: InterpretCommand): Interpretation {
        val content = complete(Prompts.INTERPRET_SYSTEM, JsonPrimitive(Prompts.interpretUserMessage(command)), "interpretation", Prompts.INTERPRET_SCHEMA)
        return ModelOutput.interpretation(content, name)
    }

    override suspend fun detectElements(command: VisionCommand): VisionResult {
        val dataUrl = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(command.jpeg)
        val userContent = buildJsonArray {
            add(buildJsonObject { put("type", "text"); put("text", Prompts.visionUserText(command.language)) })
            add(buildJsonObject {
                put("type", "image_url")
                put("image_url", buildJsonObject { put("url", dataUrl) })
            })
        }
        return ModelOutput.vision(complete(Prompts.VISION_SYSTEM, userContent, "screen_elements", Prompts.VISION_SCHEMA), name)
    }

    private suspend fun complete(system: String, userContent: JsonElement, schemaName: String, schema: JsonObject): String {
        val body = buildJsonObject {
            put("model", model)
            put("messages", JsonArray(listOf(
                buildJsonObject { put("role", "system"); put("content", system) },
                buildJsonObject { put("role", "user"); put("content", userContent) },
            )))
            put("response_format", buildJsonObject {
                put("type", "json_schema")
                put("json_schema", buildJsonObject {
                    put("name", schemaName)
                    put("strict", true)
                    put("schema", schema)
                })
            })
        }
        val response = http.post("${baseUrl.trimEnd('/')}/chat/completions") {
            bearerAuth(apiKey)
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) throw DomainException.Upstream("OpenAI-compatible provider returned ${response.status.value}")
        val root = json.parseToJsonElement(text).jsonObject
        val message = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
            ?: throw DomainException.Upstream("Provider response had no choices")
        message["refusal"]?.let { if (it is JsonPrimitive && it.isString) throw DomainException.Upstream("The model declined this request") }
        return message["content"]?.jsonPrimitive?.content ?: throw DomainException.Upstream("Provider response had no content")
    }
}
