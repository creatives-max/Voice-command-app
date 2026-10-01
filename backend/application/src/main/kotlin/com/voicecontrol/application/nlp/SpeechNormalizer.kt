// Mirrors android/core/nlp so the offline "rules" provider understands speech exactly like the phone.
package com.voicecontrol.application.nlp

import com.voicecontrol.domain.ai.FieldType

/**
 * Turns a raw recognizer transcript into the exact text to type, based on the target field type.
 * Works fully offline; the backend AI path can refine ambiguous answers further.
 */
object SpeechNormalizer {

    /** Spoken lead-ins people add before the answer: "my name is ...", "मेरा नाम ... है". */
    private val leadIns = listOf(
        Regex("^(my|the)\\s+(\\w+\\s+){0,2}(is|are)\\s+", RegexOption.IGNORE_CASE),
        Regex("^(it's|it is|its|write|type|enter|fill|put)\\s+", RegexOption.IGNORE_CASE),
        Regex("^(mera|meri|mere)\\s+(\\S+\\s+){0,2}(hai|he)\\s+", RegexOption.IGNORE_CASE),
        Regex("^(मेरा|मेरी|मेरे)\\s+(\\S+\\s+){0,2}है\\s+"),
        Regex("^(likho|likhiye|likh do|daalo|dalo|डालो|लिखो|लिखिए)\\s+", RegexOption.IGNORE_CASE),
    )
    private val trailingFillers = Regex("\\s+(hai|he|है|please|plz|likho|likh do|लिखो|daal do|डाल दो)$", RegexOption.IGNORE_CASE)
    private val hindiLeadPattern = Regex("^(?:मेरा|मेरी)\\s+\\S+\\s+(.+?)\\s+है$")
    private val hinglishLeadPattern = Regex("^(?:mera|meri)\\s+\\S+\\s+(.+?)\\s+(?:hai|he)$", RegexOption.IGNORE_CASE)

    fun stripLeadIns(speech: String): String {
        var s = speech.trim()
        // "मेरा नाम राहुल है" → "राहुल" ; "mera naam Rahul hai" → "Rahul"
        hindiLeadPattern.find(s)?.let { return it.groupValues[1] }
        hinglishLeadPattern.find(s)?.let { return it.groupValues[1] }
        leadIns.forEach { s = s.replace(it, "") }
        return s.replace(trailingFillers, "").trim()
    }

    /**
     * @param transliterate when true, Devanagari answers are converted to Latin letters for
     *   name/email/address fields (forms that only accept English characters).
     */
    fun normalize(speech: String, type: FieldType?, transliterate: Boolean = false): String {
        val answer = stripLeadIns(speech)
        return when (type) {
            FieldType.PHONE -> normalizePhone(answer)
            FieldType.OTP, FieldType.PIN, FieldType.PINCODE -> NumberParser.digitString(answer) ?: answer.filter(Char::isDigit)
            FieldType.NUMBER, FieldType.AMOUNT -> NumberParser.value(answer)?.let(NumberParser::format)
                ?: NumberParser.digitString(answer) ?: answer
            FieldType.EMAIL -> EmailNormalizer.normalize(answer)
            FieldType.DATE -> DateParser.parse(answer) ?: answer
            FieldType.NAME -> titleCase(maybeTransliterate(answer, transliterate))
            FieldType.URL -> EmailNormalizer.normalize(answer).removePrefix("@")
            FieldType.ADDRESS -> maybeTransliterate(cleanSentence(answer), transliterate)
            FieldType.PASSWORD, FieldType.TEXT, FieldType.MULTILINE, FieldType.SEARCH, null -> cleanSentence(answer)
        }
    }

    private fun normalizePhone(answer: String): String {
        val digits = NumberParser.digitString(answer) ?: return answer
        // Strip the Indian country code if spoken ("plus nine one ...", "+91...") and 10 digits remain.
        val plain = digits.removePrefix("+")
        return when {
            plain.length == 12 && plain.startsWith("91") -> plain.drop(2)
            plain.length == 11 && plain.startsWith("0") -> plain.drop(1)
            else -> plain
        }
    }

    private fun maybeTransliterate(s: String, enabled: Boolean) =
        if (enabled) Transliterator.devanagariToLatin(s) else s

    private fun titleCase(s: String): String = s.trim().split(Regex("\\s+")).joinToString(" ") { word ->
        word.lowercase().replaceFirstChar { it.titlecase() }
    }

    private fun cleanSentence(s: String): String = TextCleanup.asciiDigits(s).replace(Regex("\\s+"), " ").trim()
}
