// Mirrors android/core/nlp so the offline "rules" provider understands speech exactly like the phone.
package com.voicecontrol.application.nlp

/** Parses spoken dates ("12 March 1990", "बारह मार्च उन्नीस सौ नब्बे", "12/3/90") into dd/MM/yyyy. */
object DateParser {
    private val months = mapOf(
        "january" to 1, "jan" to 1, "जनवरी" to 1, "february" to 2, "feb" to 2, "फरवरी" to 2, "फ़रवरी" to 2,
        "march" to 3, "mar" to 3, "मार्च" to 3, "april" to 4, "apr" to 4, "अप्रैल" to 4, "may" to 5, "मई" to 5,
        "june" to 6, "jun" to 6, "जून" to 6, "july" to 7, "jul" to 7, "जुलाई" to 7, "august" to 8, "aug" to 8, "अगस्त" to 8,
        "september" to 9, "sep" to 9, "sept" to 9, "सितंबर" to 9, "सितम्बर" to 9, "october" to 10, "oct" to 10, "अक्टूबर" to 10,
        "november" to 11, "nov" to 11, "नवंबर" to 11, "नवम्बर" to 11, "december" to 12, "dec" to 12, "दिसंबर" to 12, "दिसम्बर" to 12,
    )
    private val numeric = Regex("^(\\d{1,2})[/\\-.](\\d{1,2})[/\\-.](\\d{2,4})$")
    private val ordinal = Regex("(\\d+)(st|nd|rd|th)")

    fun parse(speech: String): String? {
        val simple = TextCleanup.simplify(speech).replace(ordinal, "$1")
        numeric.find(simple.replace(" ", ""))?.let { m ->
            val (d, mo, y) = m.destructured
            return format(d.toInt(), mo.toInt(), expandYear(y.toInt()))
        }
        val tokens = simple.split(' ').filter { it.isNotBlank() && it != "of" }
        val monthIndex = tokens.indexOfFirst { it in months }
        if (monthIndex < 0) return null
        val month = months.getValue(tokens[monthIndex])
        val dayWords = tokens.subList(0, monthIndex).joinToString(" ")
        val yearWords = tokens.subList(monthIndex + 1, tokens.size).joinToString(" ")
        val day = NumberParser.value(dayWords)?.toInt() ?: tokens.getOrNull(monthIndex + 1)?.toIntOrNull()?.takeIf { it <= 31 } ?: return null
        val year = parseYear(if (dayWords.isBlank()) yearWords.substringAfter(' ', "") else yearWords) ?: return null
        return format(day, month, year)
    }

    private fun parseYear(words: String): Int? {
        if (words.isBlank()) return null
        words.trim().toIntOrNull()?.let { return expandYear(it) }
        // "nineteen ninety" / "उन्नीस सौ नब्बे" / "two thousand five"
        val tokens = words.split(' ')
        if (tokens.size == 2) {
            val a = NumberParser.value(tokens[0])?.toInt()
            val b = NumberParser.value(tokens[1])?.toInt()
            if (a != null && b != null && a in 10..20 && b in 0..99) return a * 100 + b
        }
        if ("उन्नीस" in words || "unnees" in words || "unnis" in words) {
            val rest = words.substringAfter("सौ", words.substringAfter("sau", "")).trim()
            return 1900 + (NumberParser.value(rest)?.toInt() ?: 0)
        }
        return NumberParser.value(words)?.toInt()?.let(::expandYear)
    }

    private fun expandYear(y: Int): Int = when {
        y >= 1000 -> y
        y in 0..39 -> 2000 + y
        else -> 1900 + y
    }

    private fun format(day: Int, month: Int, year: Int): String? {
        if (day !in 1..31 || month !in 1..12 || year !in 1900..2100) return null
        return "%02d/%02d/%04d".format(day, month, year)
    }
}
