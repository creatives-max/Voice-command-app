package com.voicecontrol.domain.ai

import kotlinx.serialization.Serializable

@Serializable
enum class IntentKind {
    FILL, CLICK, NEXT, PREVIOUS, SKIP, SUBMIT, BACK, SCROLL_DOWN, SCROLL_UP, REPEAT, STOP, YES, NO, CLEAR, HELP, UNKNOWN
}

@Serializable
data class FieldFill(val targetId: String, val value: String)

@Serializable
data class Interpretation(
    val intent: IntentKind,
    val targetId: String? = null,
    val value: String? = null,
    val extraFills: List<FieldFill> = emptyList(),
    val reply: String? = null,
    val confidence: Float = 1f,
    val source: String = "rules",
)

@Serializable
data class InterpretCommand(
    val screen: ScreenContext,
    val currentFieldId: String? = null,
    val utterance: String,
    val language: Language = Language.ENGLISH,
    val question: String? = null,
    val transliterate: Boolean = true,
)

/** Vision fallback: an element found on a screenshot, in image pixel coordinates. */
@Serializable
data class VisionElement(
    val id: String,
    val kind: ElementKind,
    val label: String,
    val fieldType: FieldType? = null,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

@Serializable
data class VisionResult(val elements: List<VisionElement>, val source: String)

class VisionCommand(
    val packageName: String,
    val jpeg: ByteArray,
    val width: Int,
    val height: Int,
    val language: Language,
)

/** A language-model backend. Implementations: rules (offline), Anthropic, OpenAI-compatible. */
interface LlmProvider {
    val name: String
    suspend fun interpret(command: InterpretCommand): Interpretation
    /** Detects fields/buttons on a screenshot. Providers without vision throw [UnsupportedOperationException]. */
    suspend fun detectElements(command: VisionCommand): VisionResult
}
