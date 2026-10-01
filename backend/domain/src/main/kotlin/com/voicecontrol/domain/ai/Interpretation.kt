package com.voicecontrol.domain.ai

import kotlinx.serialization.Serializable

@Serializable
enum class IntentKind {
    FILL, CLICK, NEXT, PREVIOUS, SKIP, SUBMIT, BACK, SCROLL_DOWN, SCROLL_UP, REPEAT, STOP, YES, NO, CLEAR, HELP, UNDO, READ_SCREEN, UNKNOWN
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
    /** Earlier answers in this session (never sensitive values), for references like "same as above". */
    val memory: List<MemoryItem> = emptyList(),
)

@Serializable
data class MemoryItem(val label: String, val value: String)

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

/** Asks for friendly spoken questions for a screen's fields, in the user's language. */
@Serializable
data class QuestionsCommand(val screen: ScreenContext, val language: Language = Language.ENGLISH)

/** What to ask for one field, and a short explanation to give when the user is stuck. */
@Serializable
data class FieldQuestion(val elementId: String, val question: String, val hint: String? = null)

@Serializable
data class QuestionsResult(val questions: List<FieldQuestion>, val source: String)

/** A language-model backend. Implementations: rules (offline), Anthropic, OpenAI-compatible. */
interface LlmProvider {
    val name: String
    suspend fun interpret(command: InterpretCommand): Interpretation
    /** Writes questions for the fields of a screen; providers that can't return an empty list. */
    suspend fun writeQuestions(command: QuestionsCommand): List<FieldQuestion> = emptyList()
    /** Detects fields/buttons on a screenshot. Providers without vision throw [UnsupportedOperationException]. */
    suspend fun detectElements(command: VisionCommand): VisionResult
}
