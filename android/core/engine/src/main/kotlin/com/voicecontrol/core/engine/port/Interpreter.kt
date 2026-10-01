package com.voicecontrol.core.engine.port

import com.voicecontrol.core.model.Interpretation
import com.voicecontrol.core.model.Language
import com.voicecontrol.core.model.ScreenSnapshot

/** Decides what an utterance means for the current screen (local rules or backend LLM). */
interface Interpreter {
    suspend fun interpret(request: InterpretRequest): Interpretation
}

data class InterpretRequest(
    /** Already redacted: sensitive values are never included. */
    val screen: ScreenSnapshot,
    val currentFieldId: String?,
    val utterance: String,
    val language: Language,
    /** The question that was just asked, gives the model context. */
    val question: String?,
    /** Convert Devanagari answers to Latin letters for name/email/address fields. */
    val transliterate: Boolean = true,
)
