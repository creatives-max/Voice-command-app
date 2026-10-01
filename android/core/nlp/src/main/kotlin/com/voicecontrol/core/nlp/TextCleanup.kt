package com.voicecontrol.core.nlp

/** Low-level helpers shared by the normalizers. */
internal object TextCleanup {
    private val punctuation = Regex("[\\p{P}&&[^@._\\-+/']]")
    private val spaces = Regex("\\s+")

    /** Any decimal digit (Devanagari, Bengali, Gujarati, Tamil, Telugu, …) → ASCII. */
    fun asciiDigits(s: String): String = buildString(s.length) {
        s.forEach { c ->
            val d = if (c in '0'..'9') -1 else Character.digit(c, 10)
            append(if (d >= 0) ('0' + d) else c)
        }
    }

    /** Lowercase, ASCII digits, drop sentence punctuation (keeps @ . _ - + / '), collapse spaces. */
    fun simplify(s: String): String =
        asciiDigits(s).lowercase().replace('।', ' ').replace('॥', ' ').replace(punctuation, " ").replace(spaces, " ").trim()

    fun tokens(s: String): List<String> = simplify(s).split(' ').filter { it.isNotEmpty() }
}
