package com.voicecontrol.core.engine

/**
 * Masks what must not leave the phone in screen text sent to the helper: long numbers (account, card,
 * Aadhaar, phone) keep only their last 4 digits, and codes next to words like OTP, PIN or password are
 * hidden. Amounts and short numbers stay, so "Bill amount ₹540" still reads right.
 */
object TextMask {
    private val longNumber = Regex("""\d(?:[\s-]?\d){8,}""")
    private val secretWords = Regex("""(?i)otp|one[- ]time|\bcode\b|\bpin\b|password|passcode|cvv|ओटीपी|पासवर्ड|कोड|पिन""")
    private val shortCode = Regex("""\b\d{4,8}\b""")

    fun mask(text: String): String {
        var out = longNumber.replace(text) { m ->
            val digits = m.value.filter(Char::isDigit)
            "•".repeat(digits.length - 4) + digits.takeLast(4)
        }
        if (secretWords.containsMatchIn(out)) out = shortCode.replace(out, "••••")
        return out
    }
}
