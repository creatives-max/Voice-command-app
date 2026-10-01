// Mirrors android/core/nlp so the offline "rules" provider understands speech exactly like the phone.
package com.voicecontrol.application.nlp

/**
 * Converts dictated email addresses into real ones:
 * "Rahul dot Sharma at the rate gmail dot com" → "rahul.sharma@gmail.com",
 * "राहुल एट द रेट जीमेल डॉट कॉम" → "rahul@gmail.com".
 */
object EmailNormalizer {
    private val phrases = listOf(
        "at the rate of" to "@", "at the rate" to "@", "at rate" to "@", "एट द रेट" to "@", "ऐट द रेट" to "@", "एट दी रेट" to "@",
        "एट रेट" to "@", "at sign" to "@", "underscore" to "_", "अंडरस्कोर" to "_", "under score" to "_",
        "dash" to "-", "hyphen" to "-", "डैश" to "-", "dot" to ".", "डॉट" to ".", "डोट" to ".", "point" to ".", "प्वाइंट" to ".",
        "no space" to "", "space" to "",
    )
    private val domains = mapOf(
        "जीमेल" to "gmail", "gmail" to "gmail", "g mail" to "gmail", "याहू" to "yahoo", "हॉटमेल" to "hotmail",
        "आउटलुक" to "outlook", "रेडिफमेल" to "rediffmail", "कॉम" to "com", "इन" to "in", "को" to "co", "ऑर्ग" to "org", "नेट" to "net",
    )

    fun normalize(speech: String): String {
        var s = " " + TextCleanup.asciiDigits(speech).lowercase().trim() + " "
        domains.forEach { (spoken, real) -> s = s.replace(" $spoken ", " $real ").replace(" $spoken.", " $real.") }
        phrases.forEach { (spoken, symbol) -> s = s.replace(" $spoken ", " $symbol ") }
        // Standalone "at" between two words is almost always "@" when dictating an email.
        if ('@' !in s) s = s.replace(Regex(" at (?=[\\p{L}\\p{N}]+ ?(\\.|dot))"), " @ ")
        var joined = s.replace(Regex("\\s+"), "")
        joined = Transliterator.devanagariToLatin(joined)
        return joined.trim('.', '-', '_').replace(Regex("\\.{2,}"), ".").replace("@.", "@")
    }

    private val emailRegex = Regex("^[a-z0-9._%+\\-]+@[a-z0-9.\\-]+\\.[a-z]{2,}$")

    fun isValid(email: String): Boolean = emailRegex.matches(email)
}
