// Mirrors android/core/nlp so the offline "rules" provider understands speech exactly like the phone.
package com.voicecontrol.application.nlp

/** Low-level helpers shared by the normalizers. */
internal object TextCleanup {
    private val devanagariDigits = mapOf(
        '०' to '0', '१' to '1', '२' to '2', '३' to '3', '४' to '4',
        '५' to '5', '६' to '6', '७' to '7', '८' to '8', '९' to '9',
    )
    private val punctuation = Regex("[\\p{P}&&[^@._\\-+/']]")
    private val spaces = Regex("\\s+")

    fun asciiDigits(s: String): String = buildString(s.length) { s.forEach { append(devanagariDigits[it] ?: it) } }

    /** Lowercase, ASCII digits, drop sentence punctuation (keeps @ . _ - + / '), collapse spaces. */
    fun simplify(s: String): String =
        asciiDigits(s).lowercase().replace('।', ' ').replace(punctuation, " ").replace(spaces, " ").trim()

    fun tokens(s: String): List<String> = simplify(s).split(' ').filter { it.isNotEmpty() }
}
