package com.voicecontrol.core.model

import kotlinx.serialization.Serializable

/**
 * Conversation language.
 * - [ENGLISH]: Indian English recognition and voice.
 * - [HINDI]: Devanagari recognition and Hindi voice.
 * - [HINGLISH]: code-mixed speech; recognized with en-IN (romanized), questions in Hinglish.
 * - [MARATHI], [TAMIL], [TELUGU], [BENGALI], [GUJARATI]: native-script recognition and voice; answers
 *   for name/email/address fields can be transliterated to Latin letters like Hindi.
 */
@Serializable
enum class Language(val speechTag: String, val voiceTag: String, val nativeName: String) {
    ENGLISH("en-IN", "en-IN", "English"),
    HINDI("hi-IN", "hi-IN", "हिन्दी"),
    HINGLISH("en-IN", "en-IN", "Hinglish"),
    MARATHI("mr-IN", "mr-IN", "मराठी"),
    TAMIL("ta-IN", "ta-IN", "தமிழ்"),
    TELUGU("te-IN", "te-IN", "తెలుగు"),
    BENGALI("bn-IN", "bn-IN", "বাংলা"),
    GUJARATI("gu-IN", "gu-IN", "ગુજરાતી"),
}
