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

/** What the helper does next while working towards the user's goal in an app. */
@Serializable
enum class AgentActionKind { CLICK, FILL, ASK, SCROLL_DOWN, SCROLL_UP, BACK, OPEN_APP, WAIT, DONE, GIVE_UP }

/**
 * "Do it for me": the user's goal ("pay my electricity bill"), the screen now, and what happened so far
 * (actions taken, questions asked and the user's answers; never sensitive values).
 */
@Serializable
data class AgentStepCommand(
    val goal: String,
    val screen: ScreenContext,
    val history: List<String> = emptyList(),
    val language: Language = Language.ENGLISH,
    /** Other visible text in reading order (amounts, messages, errors); long numbers and codes masked. */
    val texts: List<String> = emptyList(),
)

/**
 * One step. [say] is a short spoken line about what is being done; [question] is what to ask the user (ASK);
 * [confirm] asks the user before a press that pays, sends, deletes or submits.
 */
@Serializable
data class AgentStep(
    val action: AgentActionKind,
    val targetId: String? = null,
    val value: String? = null,
    val say: String? = null,
    val question: String? = null,
    val appName: String? = null,
    val confirm: Boolean = false,
    val source: String = "rules",
)

/** A language-model backend. Implementations: rules (offline), Anthropic, OpenAI-compatible. */
interface LlmProvider {
    val name: String
    suspend fun interpret(command: InterpretCommand): Interpretation
    /** Writes questions for the fields of a screen; providers that can't return an empty list. */
    suspend fun writeQuestions(command: QuestionsCommand): List<FieldQuestion> = emptyList()
    /** The next step towards a goal; providers that can't plan throw [UnsupportedOperationException]. */
    suspend fun nextAgentStep(command: AgentStepCommand): AgentStep = throw UnsupportedOperationException("no planning")
    /** Detects fields/buttons on a screenshot. Providers without vision throw [UnsupportedOperationException]. */
    suspend fun detectElements(command: VisionCommand): VisionResult
}
