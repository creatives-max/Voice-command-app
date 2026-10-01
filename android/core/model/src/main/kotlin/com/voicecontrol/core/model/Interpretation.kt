package com.voicecontrol.core.model

import kotlinx.serialization.Serializable

/** What the user meant by an utterance, decided locally or by the backend LLM. */
@Serializable
enum class IntentKind {
    FILL, CLICK, NEXT, PREVIOUS, SKIP, SUBMIT, BACK, SCROLL_DOWN, SCROLL_UP, REPEAT, STOP, YES, NO, CLEAR, HELP, UNDO, READ_SCREEN, UNKNOWN
}

@Serializable
data class Interpretation(
    val intent: IntentKind,
    /** Element to fill or click (may differ from the field being asked, e.g. "my email is x and phone is y"). */
    val targetId: String? = null,
    /** Normalized value to type for FILL. */
    val value: String? = null,
    /** Additional fills spoken in the same sentence. */
    val extraFills: List<FieldFill> = emptyList(),
    /** Optional short reply to speak back. */
    val reply: String? = null,
    val confidence: Float = 1f,
    /** "local" or the backend provider name, for diagnostics/history. */
    val source: String = "local",
)

@Serializable
data class FieldFill(val targetId: String, val value: String)
