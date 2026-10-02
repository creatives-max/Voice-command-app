package com.voicecontrol.core.nlp

/**
 * Matches a user-chosen wake phrase ("Hey VoiceControl", "सुनो मित्र", …) in a transcript.
 *
 * Recognizers spell the same words differently ("voice control" / "voicecontrol" / "वॉइस कंट्रोल"), so
 * both sides are transliterated to Latin letters, lowercased and stripped of spaces and punctuation,
 * then matched approximately: the phrase must appear with at most one edit per six letters.
 */
object WakeWord {
    const val DEFAULT = "hey voice control"
    private const val MIN_LENGTH = 4

    fun normalize(text: String): String =
        TextCleanup.simplify(Transliterator.toLatin(text)).filter { it.isLetterOrDigit() }

    /** A phrase must have enough letters to avoid waking on every sound. */
    fun isValidPhrase(phrase: String): Boolean = normalize(phrase).length >= MIN_LENGTH

    /** Leading words recognizers often drop or mishear ("hey" → "a", "hay", "he"). */
    private val greetings = setOf("hey", "hi", "hello", "ok", "okay", "o", "suno", "arey", "are", "arre", "he", "hay", "ae", "ai")

    /**
     * True when [transcript] contains the wake phrase. The phrase also counts without its greeting ("voice
     * control" for "hey voice control"), as long as enough letters are left.
     */
    fun matches(transcript: String, phrase: String): Boolean {
        if (matchesExactly(transcript, phrase)) return true
        val words = TextCleanup.simplify(Transliterator.toLatin(phrase)).split(' ').filter { it.isNotEmpty() }
        if (words.size < 2 || words.first() !in greetings) return false
        val rest = words.drop(1).joinToString(" ")
        return normalize(rest).length >= MIN_CORE_LENGTH && matchesExactly(transcript, rest)
    }

    private const val MIN_CORE_LENGTH = 6

    private val leadIns = setOf("please", "plz", "zara", "ज़रा", "जरा", "to", "toh", "तो", "ki", "ok", "okay", "and", "aur", "और")

    /**
     * What was said after the wake phrase in the same breath ("voice control, YouTube kholo" → "YouTube
     * kholo"), or null when nothing followed. Words are kept as the recognizer wrote them.
     */
    fun after(transcript: String, phrase: String): String? {
        val words = transcript.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        for (start in words.indices) {
            for (end in start + 1..minOf(words.size, start + MAX_PHRASE_WORDS)) {
                if (!matches(words.subList(start, end).joinToString(" "), phrase)) continue
                val rest = words.drop(end).map { it.trim(',', '.', '!', '?', '।') }.filter { it.isNotEmpty() }.toMutableList()
                while (rest.isNotEmpty() && rest.first().lowercase() in leadIns) rest.removeAt(0)
                return rest.joinToString(" ").takeIf { r -> r.count { it.isLetterOrDigit() } >= 2 }
            }
        }
        return null
    }

    private const val MAX_PHRASE_WORDS = 6

    private fun matchesExactly(transcript: String, phrase: String): Boolean {
        val p = normalize(phrase)
        if (p.length < MIN_LENGTH) return false
        val t = normalize(transcript)
        if (t.isEmpty()) return false
        if (p in t || minSubstringDistance(p, t) <= maxEdits(p)) return true
        // Same words spelled from another script ("वॉइस कंट्रोल" for "voice control"): compare consonant skeletons.
        val ps = skeleton(p)
        // Short skeletons must match exactly; longer ones tolerate one edit per eight consonants.
        return ps.length >= MIN_LENGTH && minSubstringDistance(ps, skeleton(t)) <= ps.length / 8
    }

    /** Consonant skeleton with common spelling variants folded (c/k/q, ph/f, w/v, z/j). */
    internal fun skeleton(s: String): String {
        val folded = s.replace("ph", "f").replace("sh", "s").replace("ch", "c")
            .replace(Regex("c(?=[eiy])"), "s").replace('c', 'k').replace('q', 'k').replace('w', 'v').replace('z', 'j')
        return folded.filter { it !in "aeiouyh" || it == folded.first() }
    }

    /** Edits tolerated for a phrase of this (normalized) length. */
    fun maxEdits(phrase: String): Int = (phrase.length / 6).coerceAtLeast(1)

    /** Smallest edit distance between [p] and any substring of [t] (approximate string matching). */
    private fun minSubstringDistance(p: String, t: String): Int {
        var prev = IntArray(t.length + 1) // free start anywhere in t
        for (i in 1..p.length) {
            val cur = IntArray(t.length + 1)
            cur[0] = i
            for (j in 1..t.length) {
                val cost = if (p[i - 1] == t[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            prev = cur
        }
        return prev.min()
    }
}
