package com.voicecontrol.core.engine

import java.text.Normalizer

/**
 * A voice macro: saying [phrase] runs a saved flow ("pay electricity bill", "बिजली का बिल").
 * Set on the dashboard (a VOICE trigger, [triggerId]) or on the phone (local, no trigger id).
 */
data class VoiceShortcut(val phrase: String, val flowId: String, val flowName: String? = null, val triggerId: String? = null)

/**
 * Matches what the user said to their voice shortcuts. Polite words around the phrase ("please …",
 * "… karo", "… now") are ignored, small recognition slips are tolerated, and an unclear tie matches
 * nothing. The backend uses the same [normalize] to keep phrases unique per account.
 */
object ShortcutMatcher {
    const val MIN_LENGTH = 2
    const val MAX_LENGTH = 60
    private const val THRESHOLD = 0.84

    private val fillers = setOf(
        "please", "pls", "plz", "now", "run", "start", "do", "the", "my", "kindly", "hey", "ok", "okay",
        "karo", "kar", "kardo", "chalao", "chala", "shuru", "abhi", "zara", "jara", "na",
        "करो", "कर", "दो", "चलाओ", "शुरू", "अभी", "ज़रा", "कृपया", "प्लीज़",
    )

    /** Lower case, accents and punctuation removed, single spaces. Script (Devanagari, Tamil…) is kept. */
    fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFC)
            .replace(Regex("[\\p{Punct}।॥“”‘’]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** [normalize] without leading/trailing filler words; the phrase itself is never emptied. */
    fun core(text: String): String {
        val words = normalize(text).split(' ').filter { it.isNotEmpty() }.toMutableList()
        while (words.size > 1 && words.first() in fillers) words.removeAt(0)
        while (words.size > 1 && words.last() in fillers) words.removeAt(words.lastIndex)
        return words.joinToString(" ")
    }

    /** Null when the phrase can be used, otherwise why not. */
    fun validate(phrase: String): String? {
        val n = normalize(phrase)
        return when {
            n.length < MIN_LENGTH -> "Say at least $MIN_LENGTH letters"
            n.length > MAX_LENGTH -> "Keep it under $MAX_LENGTH letters"
            n in reserved -> "\"$phrase\" is already a VoiceControl command"
            else -> null
        }
    }

    /** Words VoiceControl itself understands while a session runs; a shortcut can't be one of them. */
    private val reserved = setOf(
        "stop", "back", "next", "skip", "repeat", "help", "undo", "submit", "scroll", "scroll down", "scroll up",
        "yes", "no", "haan", "nahi", "read screen", "ruko", "band karo", "wapas", "aage", "peeche",
    )

    /** An existing shortcut that already uses [phrase] (ignoring case, punctuation and polite words). */
    fun conflict(phrase: String, existing: List<VoiceShortcut>): VoiceShortcut? {
        val key = core(phrase)
        return existing.firstOrNull { core(it.phrase) == key }
    }

    /** The shortcut the user meant, or null when nothing (or more than one, equally) fits. */
    fun match(utterance: String, shortcuts: List<VoiceShortcut>): VoiceShortcut? {
        val said = core(utterance)
        if (said.isEmpty() || shortcuts.isEmpty()) return null
        val scored = shortcuts.map { it to score(said, core(it.phrase)) }.filter { it.second >= THRESHOLD }
        val best = scored.maxByOrNull { it.second } ?: return null
        val tied = scored.filter { it.second == best.second }.map { it.first.flowId }.distinct()
        return if (tied.size == 1) best.first else null
    }

    private fun score(said: String, phrase: String): Double {
        if (phrase.isEmpty()) return 0.0
        if (said == phrase) return 1.0
        // The phrase said inside a longer sentence: "can you pay electricity bill for me".
        if (" $said ".contains(" $phrase ")) return 0.95
        val distance = levenshtein(said, phrase)
        return 1.0 - distance.toDouble() / maxOf(said.length, phrase.length)
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }
}
