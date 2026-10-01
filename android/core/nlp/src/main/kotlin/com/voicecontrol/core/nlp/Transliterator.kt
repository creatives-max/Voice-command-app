package com.voicecontrol.core.nlp

/**
 * Minimal Devanagari → Latin transliteration (Hunterian-like) so a Hindi-dictated name such as
 * "राहुल शर्मा" can be typed as "Rahul Sharma" into forms that expect English letters.
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
        'ळ' to "l", '\u0958' to "q", '\u0959' to "kh", '\u095A' to "g", '\u095B' to "z", '\u095C' to "r", '\u095D' to "rh", '\u095E' to "f",
    )
    private val nuktaForms = mapOf('क' to "q", 'ख' to "kh", 'ग' to "g", 'ज' to "z", 'ड' to "r", 'ढ' to "rh", 'फ' to "f")
    private val vowels = mapOf(
        'अ' to "a", 'आ' to "aa", 'इ' to "i", 'ई' to "i", 'उ' to "u", 'ऊ' to "u", 'ऋ' to "ri",
        'ए' to "e", 'ऐ' to "ai", 'ओ' to "o", 'औ' to "au", 'ऑ' to "o",
    )
    private val matras = mapOf(
        'ा' to "a", 'ि' to "i", 'ी' to "i", 'ु' to "u", 'ू' to "u", 'ृ' to "ri",
        'े' to "e", 'ै' to "ai", 'ो' to "o", 'ौ' to "au", 'ॉ' to "o",
    )
    private val labials = setOf('प', 'फ', 'ब', 'भ', 'म')
    private val signs = mapOf('ं' to "n", 'ँ' to "n", 'ः' to "h", '।' to ".")

    fun containsDevanagari(s: String): Boolean = s.any { it in 'ऀ'..'ॿ' }

    fun devanagariToLatin(input: String): String {
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
                        if (!atWordEnd) out.append('a')
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
