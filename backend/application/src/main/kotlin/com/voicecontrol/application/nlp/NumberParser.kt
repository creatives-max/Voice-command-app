// Mirrors android/core/nlp so the offline "rules" provider understands speech exactly like the phone.
package com.voicecontrol.application.nlp

/**
 * Understands spoken numbers in English, Hindi (Devanagari), Hinglish (romanized Hindi), and digits,
 * tens and multipliers in Marathi, Tamil, Telugu, Bengali and Gujarati (native digits are converted too).
 *
 * Two modes:
 * - [digitString]: phone / OTP / PIN-code style, read digit by digit
 *   ("nine eight double seven", "नौ आठ सात", "nau aath saat", "98 77 6") → "98776".
 * - [value]: quantities and amounts ("two thousand five hundred", "ढाई हज़ार", "paanch sau") → 2500, 500.
 */
object NumberParser {

    private val digitWords: Map<String, Int> = buildMap {
        // English
        listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine").forEachIndexed { i, w -> put(w, i) }
        put("oh", 0); put("o", 0); put("nil", 0)
        // Hinglish
        put("shunya", 0); put("shoonya", 0); put("sifar", 0)
        put("ek", 1); put("do", 2); put("teen", 3); put("tin", 3); put("char", 4); put("chaar", 4)
        put("paanch", 5); put("panch", 5); put("chhe", 6); put("chhah", 6); put("che", 6); put("chah", 6)
        put("saat", 7); put("sat", 7); put("aath", 8); put("ath", 8); put("nau", 9); put("no", -1)
        // Devanagari
        put("शून्य", 0); put("ज़ीरो", 0); put("जीरो", 0); put("एक", 1); put("दो", 2); put("तीन", 3); put("चार", 4)
        put("पांच", 5); put("पाँच", 5); put("छह", 6); put("छः", 6); put("छे", 6); put("सात", 7); put("आठ", 8); put("नौ", 9)
        // Marathi
        put("दोन", 2); put("पाच", 5); put("सहा", 6); put("नऊ", 9)
        // Tamil
        listOf("பூஜ்ஜியம்", "பூஜ்யம்", "சுழியம்").forEach { put(it, 0) }
        listOf("ஒன்று", "இரண்டு", "மூன்று", "நான்கு", "ஐந்து", "ஆறு", "ஏழு", "எட்டு", "ஒன்பது").forEachIndexed { i, w -> put(w, i + 1) }
        // Telugu
        listOf("సున్నా", "ఒకటి", "రెండు", "మూడు", "నాలుగు", "ఐదు", "ఆరు", "ఏడు", "ఎనిమిది", "తొమ్మిది").forEachIndexed { i, w -> put(w, i) }
        // Bengali
        listOf("শূন্য", "এক", "দুই", "তিন", "চার", "পাঁচ", "ছয়", "সাত", "আট", "নয়").forEachIndexed { i, w -> put(w, i) }
        // Gujarati
        listOf("શૂન્ય", "એક", "બે", "ત્રણ", "ચાર", "પાંચ", "છ", "સાત", "આઠ", "નવ").forEachIndexed { i, w -> put(w, i) }
    }.filterValues { it >= 0 }

    private val repeaters = mapOf(
        "double" to 2, "triple" to 3, "dabal" to 2, "trible" to 3, "डबल" to 2, "ट्रिपल" to 3,
    )

    private val smallValues: Map<String, Long> = buildMap {
        listOf(
            "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
            "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
        ).forEachIndexed { i, w -> put(w, i.toLong()) }
        listOf("twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
            .forEachIndexed { i, w -> put(w, (i + 2) * 10L) }
        digitWords.forEach { (w, v) -> if (w !in this) put(w, v.toLong()) }
        // Hindi tens and common numbers (romanized + Devanagari)
        mapOf(
            "das" to 10, "dus" to 10, "दस" to 10, "gyarah" to 11, "barah" to 12, "baarah" to 12, "बारह" to 12,
            "pandrah" to 15, "पंद्रह" to 15, "bees" to 20, "बीस" to 20, "pachchis" to 25, "pachis" to 25, "पच्चीस" to 25,
            "tees" to 30, "तीस" to 30, "chalis" to 40, "chaalis" to 40, "चालीस" to 40, "pachaas" to 50, "pachas" to 50,
            "पचास" to 50, "saath" to 60, "साठ" to 60, "sattar" to 70, "सत्तर" to 70, "assi" to 80, "अस्सी" to 80,
            "nabbe" to 90, "नब्बे" to 90,
            // ten / twenty in Marathi, Tamil, Telugu, Bengali, Gujarati
            "दहा" to 10, "वीस" to 20, "பத்து" to 10, "இருபது" to 20, "పది" to 10, "ఇరవై" to 20,
            "দশ" to 10, "কুড়ি" to 20, "বিশ" to 20, "દસ" to 10, "વીસ" to 20,
        ).forEach { (w, v) -> put(w, v.toLong()) }
    }

    private val multipliers = mapOf(
        "hundred" to 100L, "sau" to 100L, "सौ" to 100L, "शंभर" to 100L, "நூறு" to 100L, "వంద" to 100L, "একশো" to 100L, "শো" to 100L, "સો" to 100L,
        "thousand" to 1_000L, "hazaar" to 1_000L, "hazar" to 1_000L, "हज़ार" to 1_000L, "हजार" to 1_000L,
        "ஆயிரம்" to 1_000L, "వేయి" to 1_000L, "వెయ్యి" to 1_000L, "হাজার" to 1_000L, "હજાર" to 1_000L,
        "lakh" to 100_000L, "lac" to 100_000L, "लाख" to 100_000L, "லட்சம்" to 100_000L, "లక్ష" to 100_000L, "লাখ" to 100_000L, "লক্ষ" to 100_000L, "લાખ" to 100_000L,
        "million" to 1_000_000L,
        "crore" to 10_000_000L, "karod" to 10_000_000L, "करोड़" to 10_000_000L,
    )

    private val fractions = mapOf("dedh" to 1.5, "डेढ़" to 1.5, "dhai" to 2.5, "dhaai" to 2.5, "ढाई" to 2.5, "sawa" to 1.25, "सवा" to 1.25)

    private val fillers = setOf("and", "aur", "और", "plus", "number", "is", "hai", "है", "mera", "meri", "मेरा", "मेरी", "my")

    /** Returns only digits (keeps a leading '+' for country codes) or null if the speech has no digits. */
    fun digitString(speech: String): String? {
        val tokens = TextCleanup.tokens(speech.replace('-', ' ').replace('.', ' '))
        val out = StringBuilder()
        var pendingRepeat = 1
        var i = 0
        fun emit(digits: String) {
            out.append(digits.first().toString().repeat(pendingRepeat)).append(digits.drop(1))
            pendingRepeat = 1
        }
        while (i < tokens.size) {
            val token = tokens[i++]
            if (token in fillers) continue
            val repeat = repeaters[token]
            if (repeat != null) {
                pendingRepeat = repeat
                continue
            }
            when {
                token.startsWith("+") && token.length > 1 && token.drop(1).all(Char::isDigit) && out.isEmpty() -> out.append(token)
                token.all(Char::isDigit) -> emit(token)
                token in digitWords -> emit(digitWords.getValue(token).toString())
                token in smallValues && smallValues.getValue(token) in 10..99 -> {
                    var number = smallValues.getValue(token)
                    // "ninety eight" → 98
                    val next = tokens.getOrNull(i)
                    if (number % 10 == 0L && number >= 20 && next != null && next in digitWords && digitWords.getValue(next) in 1..9) {
                        number += digitWords.getValue(next)
                        i++
                    }
                    emit(number.toString())
                }
                else -> Unit // ignore words such as "my number is"
            }
        }
        return out.toString().takeIf { it.any(Char::isDigit) }
    }

    /** Parses a spoken quantity into a number, or null when none found. */
    fun value(speech: String): Double? {
        val tokens = TextCleanup.tokens(speech).map { it.replace(",", "") }
        if (tokens.isEmpty()) return null
        tokens.singleOrNull()?.toDoubleOrNull()?.let { return it }

        var total = 0.0
        var current = 0.0
        var found = false
        var pendingFraction: Double? = null
        for (token in tokens) {
            if (token in fillers) continue
            val numeric = token.toDoubleOrNull()
            when {
                numeric != null -> { current += numeric; found = true }
                token in fractions -> { pendingFraction = fractions.getValue(token); found = true }
                token in smallValues -> { current += smallValues.getValue(token); found = true }
                token in multipliers -> {
                    val m = multipliers.getValue(token)
                    val base = pendingFraction ?: if (current == 0.0) 1.0 else current
                    pendingFraction = null
                    if (m >= 1000) {
                        total += base * m
                        current = 0.0
                    } else {
                        current = base * m
                    }
                    found = true
                }
                else -> if (found) break
            }
        }
        if (!found) return null
        return total + current + (pendingFraction ?: 0.0)
    }

    /** Formats a parsed value without a trailing ".0". */
    fun format(value: Double): String = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
}
