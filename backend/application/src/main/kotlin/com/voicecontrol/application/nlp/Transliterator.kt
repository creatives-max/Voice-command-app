// Mirrors android/core/nlp so the offline "rules" provider understands speech exactly like the phone.
package com.voicecontrol.application.nlp

/**
 * Minimal Indic → Latin transliteration (Hunterian-like) so a dictated name such as "राहुल शर्मा",
 * "রাহুল", "રાહુલ", "రాహుల్" or "ராகுல்" can be typed as "Rahul" into forms that expect English letters.
 *
 * Bengali, Gujarati, Tamil and Telugu share Devanagari's (ISCII) layout, so their letters are first
 * mapped to Devanagari by Unicode block offset and then transliterated with the same rules.
 */
object Transliterator {
    private const val VIRAMA = '्'
    private const val NUKTA = '़'

    private val consonants = mapOf(
        'क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh", 'ङ' to "n",
        'च' to "ch", 'छ' to "chh", 'ज' to "j", 'झ' to "jh", 'ञ' to "n",
        'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n",
        'त' to "t", 'थ' to "th", 'द' to "d", 'ध' to "dh", 'न' to "n",
        'प' to "p", 'फ' to "ph", 'ब' to "b", 'भ' to "bh", 'म' to "m",
        'य' to "y", 'र' to "r", 'ल' to "l", 'व' to "v", 'श' to "sh", 'ष' to "sh", 'स' to "s", 'ह' to "h",
        'ळ' to "l", 'ऴ' to "zh", 'ऱ' to "r", 'ऩ' to "n", '\u095F' to "y", '\u0958' to "q", '\u0959' to "kh", '\u095A' to "g", '\u095B' to "z", '\u095C' to "r", '\u095D' to "rh", '\u095E' to "f",
    )
    private val nuktaForms = mapOf('क' to "q", 'ख' to "kh", 'ग' to "g", 'ज' to "z", 'ड' to "r", 'ढ' to "rh", 'फ' to "f")
    private val vowels = mapOf(
        'अ' to "a", 'आ' to "aa", 'इ' to "i", 'ई' to "i", 'उ' to "u", 'ऊ' to "u", 'ऋ' to "ri",
        'ए' to "e", 'ऐ' to "ai", 'ओ' to "o", 'औ' to "au", 'ऑ' to "o", 'ऎ' to "e", 'ऒ' to "o",
    )
    private val matras = mapOf(
        'ा' to "a", 'ि' to "i", 'ी' to "i", 'ु' to "u", 'ू' to "u", 'ृ' to "ri",
        'े' to "e", 'ै' to "ai", 'ो' to "o", 'ौ' to "au", 'ॉ' to "o", 'ॆ' to "e", 'ॊ' to "o",
        // Length marks of Tamil/Telugu/Bengali vowels once mapped to Devanagari.
        '\u0957' to "au", '\u0955' to "",
    )
    private val labials = setOf('प', 'फ', 'ब', 'भ', 'म')
    private val signs = mapOf('ं' to "n", 'ँ' to "n", 'ः' to "h", '।' to ".")

    /** Unicode blocks laid out like Devanagari (offset from U+0900). */
    private val indicBlocks = listOf(0x0980, 0x0A80, 0x0B80, 0x0C00) // Bengali, Gujarati, Tamil, Telugu

    fun containsDevanagari(s: String): Boolean = s.any { it in 'ऀ'..'ॿ' }

    fun containsIndic(s: String): Boolean = s.any { c -> c in 'ऀ'..'ॿ' || indicBlocks.any { c.code in it until it + 0x80 } }

    /** Maps Bengali/Gujarati/Tamil/Telugu letters onto the equivalent Devanagari letters. */
    fun toDevanagari(input: String): String = buildString(input.length) {
        input.forEach { c ->
            val block = indicBlocks.firstOrNull { c.code in it until it + 0x80 }
            when {
                c == 'ৎ' -> append('त').append(VIRAMA) // Bengali khanda ta
                block != null && !Character.isDigit(c) -> append((c.code - block + 0x0900).toChar())
                else -> append(c)
            }
        }
    }

    /**
     * Any supported Indic script → Latin letters. Telugu and Tamil words keep their final vowel
     * ("ప్రియ" → "priya"), unlike Hindi/Marathi where it is silent ("राम" → "ram").
     */
    fun toLatin(input: String): String {
        if (!containsIndic(input)) return input
        val keepFinalA = input.any { it.code in 0x0B80..0x0C7F }
        return devanagariToLatin(toDevanagari(input), schwaDeletion = !keepFinalA)
    }

    fun devanagariToLatin(input: String, schwaDeletion: Boolean = true): String {
        if (!containsDevanagari(input)) return input
        val out = StringBuilder()
        var i = 0
        while (i < input.length) {
            val c = input[i]
            val base = consonants[c]
            if (base != null) {
                var latin = base
                var j = i + 1
                if (input.getOrNull(j) == NUKTA) {
                    latin = nuktaForms[c] ?: base
                    j++
                }
                val next = input.getOrNull(j)
                when {
                    next == VIRAMA -> { out.append(latin); i = j + 1 }
                    next != null && next in matras -> { out.append(latin).append(matras.getValue(next)); i = j + 1 }
                    else -> {
                        // Inherent "a" is dropped at word end (schwa deletion): "राहुल" → "rahul", "राम" → "ram".
                        val atWordEnd = next == null || !isDevanagariLetter(next)
                        out.append(latin)
                        if (!atWordEnd || !schwaDeletion) out.append('a')
                        i = j
                    }
                }
                continue
            }
            // Anusvara before a labial consonant sounds like "m": मुंबई → mumbai, चंपा → champa.
            if (c == 'ं' && input.getOrNull(i + 1) in labials) {
                out.append('m')
                i++
                continue
            }
            out.append(vowels[c] ?: matras[c] ?: signs[c] ?: c.toString())
            i++
        }
        return out.toString()
    }

    private fun isDevanagariLetter(c: Char) = c in consonants || c in vowels || c in matras || c == NUKTA || c == VIRAMA || c in signs && c != '।'
}
