package com.voicecontrol.application.ai

import com.voicecontrol.domain.ai.InterpretCommand
import com.voicecontrol.domain.ai.Language
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Prompts and JSON schemas shared by every LLM provider. */
object Prompts {
    private const val MAX_MEMORY = 12

    val INTERPRET_SYSTEM = """
        You are the language brain of VoiceControl, an Android assistant that fills forms in other apps by voice.
        The user speaks English, Hindi (Devanagari), Hinglish (romanized Hindi mixed with English), Marathi, Tamil,
        Telugu, Bengali or Gujarati (native script, often mixed with English words).
        You receive the current screen (fields and buttons with ids), the field currently being asked about,
        the question that was asked, and the user's utterance. Decide what the user wants.

        Intents:
        - FILL: the user gave a value. Put the field id in targetId (usually the current field) and the exact text
          to type in value. If the user also gave values for other visible fields in the same sentence, add them to
          extraFills.
        - CLICK: the user wants a button/link/checkbox pressed. Put its id in targetId.
        - NEXT, PREVIOUS, SKIP, SUBMIT, BACK, SCROLL_DOWN, SCROLL_UP, REPEAT, STOP, YES, NO, CLEAR, HELP: control commands.
        - UNKNOWN: you cannot tell.

        Value rules:
        - Normalize to what the field expects: phone → 10 digits (drop +91/0), email → lowercase "a.b@c.com" from
          dictation like "at the rate"/"dot", dates → DD/MM/YYYY, numbers → digits, pincode → 6 digits,
          names → Title Case.
        - Spoken lead-ins are not part of the value ("my name is", "मेरा नाम ... है", "mera number ... hai").
        - Number words in any of these languages (एक, दो, ek, do, sau, hazaar, lakh, dedh, dhai, ஒன்று, రెండు, তিন, ચાર…)
          and native digits (৯, ૪, ௫, ౬…) become ASCII digits.
        - "memory" lists earlier answers in this session. Resolve references to them: "same as above", "same as
          permanent address", "his name", "that one", "wahi", "वही", "அதே", "అదే", "একই", "એ જ" → the referred value.
        - If transliterate is true and the field is a name/email/address, write Indic-script words in Latin letters.
        - Never fill, guess or output values for password, OTP or PIN fields; never target them.
        - Only use ids that exist on the screen.
        Keep "reply" empty unless a short clarification would help the user. Respond only with the JSON object.
    """.trimIndent()

    val VISION_SYSTEM = """
        You see a screenshot of an Android app. List every input field and tappable button a user could interact with.
        For each element give: a short id (e1, e2, …), kind (TEXT_FIELD, BUTTON, CHECKBOX, SWITCH, RADIO, DROPDOWN, LINK),
        its visible label (or a short description if unlabeled), fieldType for text fields (TEXT, NAME, EMAIL, PHONE,
        NUMBER, PASSWORD, OTP, PIN, DATE, ADDRESS, PINCODE, MULTILINE, SEARCH, URL, AMOUNT) or "" for others,
        and its bounding box in image pixels (x, y, width, height). Ignore the status bar and the round
        VoiceControl microphone bubble. Respond only with the JSON object.
    """.trimIndent()

    fun interpretUserMessage(c: InterpretCommand): String = buildJsonObject {
        put("language", c.language.name)
        put("transliterate", c.transliterate)
        c.question?.let { put("question", it) }
        c.currentFieldId?.let { put("currentFieldId", it) }
        put("utterance", c.utterance)
        if (c.memory.isNotEmpty()) {
            putJsonArray("memory") {
                c.memory.takeLast(MAX_MEMORY).forEach { m -> add(buildJsonObject { put("label", m.label.take(80)); put("value", m.value.take(200)) }) }
            }
        }
        putJsonObject("screen") {
            put("app", c.screen.packageName)
            c.screen.title?.let { put("title", it) }
            putJsonArray("elements") {
                c.screen.elements.forEach { e ->
                    add(buildJsonObject {
                        put("id", e.id)
                        put("kind", e.kind.name)
                        put("label", e.label)
                        e.fieldType?.let { put("fieldType", it.name) }
                        e.hint?.let { put("hint", it) }
                        if (!e.sensitive) e.value?.let { put("value", it) }
                        if (e.sensitive) put("sensitive", true)
                        e.isChecked?.let { put("checked", it) }
                    })
                }
            }
        }
    }.toString()

    fun visionUserText(language: Language) = "Screen language hint: ${language.name}. List the interactive elements."

    private fun str() = buildJsonObject { put("type", "string") }
    private fun enumOf(values: List<String>) = buildJsonObject {
        put("type", "string")
        put("enum", JsonArray(values.map(::JsonPrimitive)))
    }
    private fun objectOf(props: Map<String, JsonObject>) = buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(props))
        put("required", JsonArray(props.keys.map(::JsonPrimitive)))
        put("additionalProperties", false)
    }

    /** JSON schema for interpretations. Strings use "" for "none" so every field can be required (strict mode). */
    val INTERPRET_SCHEMA: JsonObject = objectOf(
        mapOf(
            "intent" to enumOf(com.voicecontrol.domain.ai.IntentKind.entries.map { it.name }),
            "targetId" to str(),
            "value" to str(),
            "extraFills" to buildJsonObject {
                put("type", "array")
                put("items", objectOf(mapOf("targetId" to str(), "value" to str())))
            },
            "reply" to str(),
            "confidence" to buildJsonObject { put("type", "number") },
        ),
    )

    val VISION_SCHEMA: JsonObject = objectOf(
        mapOf(
            "elements" to buildJsonObject {
                put("type", "array")
                put(
                    "items",
                    objectOf(
                        mapOf(
                            "id" to str(),
                            "kind" to enumOf(com.voicecontrol.domain.ai.ElementKind.entries.map { it.name }),
                            "label" to str(),
                            "fieldType" to enumOf(listOf("") + com.voicecontrol.domain.ai.FieldType.entries.map { it.name }),
                            "x" to buildJsonObject { put("type", "integer") },
                            "y" to buildJsonObject { put("type", "integer") },
                            "width" to buildJsonObject { put("type", "integer") },
                            "height" to buildJsonObject { put("type", "integer") },
                        ),
                    ),
                )
            },
        ),
    )
}
