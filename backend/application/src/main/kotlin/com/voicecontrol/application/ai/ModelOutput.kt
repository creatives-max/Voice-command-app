package com.voicecontrol.application.ai

import com.voicecontrol.domain.ai.ElementKind
import com.voicecontrol.domain.ai.FieldFill
import com.voicecontrol.domain.ai.FieldQuestion
import com.voicecontrol.domain.ai.FieldType
import com.voicecontrol.domain.ai.IntentKind
import com.voicecontrol.domain.ai.Interpretation
import com.voicecontrol.domain.ai.VisionElement
import com.voicecontrol.domain.ai.VisionResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Parses the JSON objects that models return for [Prompts.INTERPRET_SCHEMA] / [Prompts.VISION_SCHEMA]. */
object ModelOutput {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Extracts the first JSON object from text (tolerates code fences or stray prose). */
    fun extractObject(text: String): JsonObject {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        require(start >= 0 && end > start) { "model returned no JSON object" }
        return json.parseToJsonElement(text.substring(start, end + 1)).jsonObject
    }

    private fun JsonObject.str(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    fun interpretation(text: String, source: String): Interpretation {
        val o = extractObject(text)
        val intent = o.str("intent")?.let { runCatching { IntentKind.valueOf(it.uppercase()) }.getOrNull() } ?: IntentKind.UNKNOWN
        val extras = (o["extraFills"]?.jsonArray ?: emptyList()).mapNotNull { el ->
            val f = el.jsonObject
            val id = f.str("targetId") ?: return@mapNotNull null
            val value = f.str("value") ?: return@mapNotNull null
            FieldFill(id, value)
        }
        return Interpretation(
            intent = intent,
            targetId = o.str("targetId"),
            value = o.str("value"),
            extraFills = extras,
            reply = o.str("reply"),
            confidence = o["confidence"]?.jsonPrimitive?.floatOrNull?.coerceIn(0f, 1f) ?: 0.8f,
            source = source,
        )
    }

    fun agentStep(text: String, source: String): com.voicecontrol.domain.ai.AgentStep {
        val o = extractObject(text)
        val action = o.str("action")?.let { name -> com.voicecontrol.domain.ai.AgentActionKind.entries.firstOrNull { it.name == name } }
            ?: com.voicecontrol.domain.ai.AgentActionKind.GIVE_UP
        return com.voicecontrol.domain.ai.AgentStep(
            action = action,
            targetId = o.str("targetId"),
            value = o.str("value"),
            say = o.str("say"),
            question = o.str("question"),
            appName = o.str("appName"),
            confirm = (o["confirm"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true",
            source = source,
        )
    }

    fun questions(text: String): List<FieldQuestion> {
        val o = extractObject(text)
        return (o["questions"]?.jsonArray ?: emptyList()).mapNotNull { el ->
            val q = el.jsonObject
            val id = q.str("elementId") ?: return@mapNotNull null
            val question = q.str("question") ?: return@mapNotNull null
            FieldQuestion(id, question, q.str("hint"))
        }
    }

    fun vision(text: String, source: String): VisionResult {
        val o = extractObject(text)
        val elements = (o["elements"]?.jsonArray ?: emptyList()).mapIndexedNotNull { index, el ->
            val e = el.jsonObject
            val kind = e.str("kind")?.let { runCatching { ElementKind.valueOf(it.uppercase()) }.getOrNull() } ?: return@mapIndexedNotNull null
            val w = e["width"]?.jsonPrimitive?.intOrNull ?: return@mapIndexedNotNull null
            val h = e["height"]?.jsonPrimitive?.intOrNull ?: return@mapIndexedNotNull null
            if (w <= 0 || h <= 0) return@mapIndexedNotNull null
            VisionElement(
                id = "vision:" + (e.str("id") ?: "e$index"),
                kind = kind,
                label = e.str("label") ?: kind.name.lowercase(),
                fieldType = e.str("fieldType")?.let { runCatching { FieldType.valueOf(it.uppercase()) }.getOrNull() },
                x = e["x"]?.jsonPrimitive?.intOrNull ?: 0,
                y = e["y"]?.jsonPrimitive?.intOrNull ?: 0,
                width = w,
                height = h,
            )
        }
        return VisionResult(elements, source)
    }
}
