package com.voicecontrol.application.automation

import java.text.Normalizer

/**
 * Phrases of voice shortcuts (VOICE triggers). Same rules as the phone's `ShortcutMatcher`
 * (android/core/engine/.../VoiceShortcuts.kt): [key] is its `normalize`, [validate] its `validate`.
 */
object VoicePhrases {
    const val MIN_LENGTH = 2
    const val MAX_LENGTH = 60

    private val reserved = setOf(
        "stop", "back", "next", "skip", "repeat", "help", "undo", "submit", "scroll", "scroll down", "scroll up",
        "yes", "no", "haan", "nahi", "read screen", "ruko", "band karo", "wapas", "aage", "peeche",
    )

    /** Lower case, punctuation removed, single spaces. */
    fun key(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFC)
            .replace(Regex("[\\p{Punct}।॥“”‘’]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    fun validate(phrase: String): String? {
        val n = key(phrase)
        return when {
            n.length < MIN_LENGTH -> "Say at least $MIN_LENGTH letters"
            n.length > MAX_LENGTH -> "Keep it under $MAX_LENGTH letters"
            n in reserved -> "\"$phrase\" is already a VoiceControl command"
            else -> null
        }
    }
}
