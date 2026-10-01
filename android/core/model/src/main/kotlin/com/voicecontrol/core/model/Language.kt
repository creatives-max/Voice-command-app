package com.voicecontrol.core.model

import kotlinx.serialization.Serializable

/**
 * Conversation language.
 * - [ENGLISH]: Indian English recognition and voice.
 * - [HINDI]: Devanagari recognition and Hindi voice.
 * - [HINGLISH]: code-mixed speech; recognized with en-IN (romanized), questions in Hinglish.
 */
@Serializable
enum class Language(val speechTag: String, val voiceTag: String) {
    ENGLISH("en-IN", "en-IN"),
    HINDI("hi-IN", "hi-IN"),
    HINGLISH("en-IN", "en-IN"),
}
