package com.voicecontrol.core.screen

import com.voicecontrol.core.model.FieldType
import com.voicecontrol.core.screen.InputTypeBits.TYPE_CLASS_DATETIME
import com.voicecontrol.core.screen.InputTypeBits.TYPE_CLASS_NUMBER
import com.voicecontrol.core.screen.InputTypeBits.TYPE_CLASS_PHONE
import com.voicecontrol.core.screen.InputTypeBits.TYPE_CLASS_TEXT
import com.voicecontrol.core.screen.InputTypeBits.TYPE_MASK_CLASS
import com.voicecontrol.core.screen.InputTypeBits.TYPE_MASK_VARIATION
import com.voicecontrol.core.screen.InputTypeBits.TYPE_NUMBER_FLAG_DECIMAL
import com.voicecontrol.core.screen.InputTypeBits.TYPE_NUMBER_VARIATION_PASSWORD
import com.voicecontrol.core.screen.InputTypeBits.TYPE_TEXT_FLAG_MULTI_LINE
import com.voicecontrol.core.screen.InputTypeBits.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
import com.voicecontrol.core.screen.InputTypeBits.TYPE_TEXT_VARIATION_PASSWORD
import com.voicecontrol.core.screen.InputTypeBits.TYPE_TEXT_VARIATION_PERSON_NAME
import com.voicecontrol.core.screen.InputTypeBits.TYPE_TEXT_VARIATION_POSTAL_ADDRESS
import com.voicecontrol.core.screen.InputTypeBits.TYPE_TEXT_VARIATION_URI
import com.voicecontrol.core.screen.InputTypeBits.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
import com.voicecontrol.core.screen.InputTypeBits.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
import com.voicecontrol.core.screen.InputTypeBits.TYPE_TEXT_VARIATION_WEB_PASSWORD

/**
 * Decides the semantic [FieldType] of an editable node from its input-type bits, its label
 * and its view id. Sensitive detection (password / OTP / PIN / CVV) errs on the side of masking.
 */
object FieldClassifier {

    private val otpWords = listOf("otp", "one time", "one-time", "verification code", "verify code", "sms code", "ओटीपी", "सत्यापन कोड")
    private val pinWords = listOf("pin", "mpin", "upi pin", "cvv", "cvc", "security code", "passcode", "पिन")
    private val passwordWords = listOf("password", "passwd", "pwd", "पासवर्ड", "secret")
    private val emailWords = listOf("email", "e-mail", "mail id", "ईमेल")
    private val phoneWords = listOf("phone", "mobile", "contact number", "whatsapp", "मोबाइल", "फ़ोन", "फोन")
    private val pincodeWords = listOf("pincode", "pin code", "postal code", "zip", "पिनकोड")
    private val dateWords = listOf("date", "dob", "birth", "जन्म", "तारीख")
    private val addressWords = listOf("address", "street", "locality", "पता")
    private val nameWords = listOf("name", "नाम")
    private val searchWords = listOf("search", "खोज")
    private val amountWords = listOf("amount", "price", "₹", "rupees", "राशि")

    fun classify(node: UiNode, label: String?): FieldType {
        val bits = node.inputType
        val cls = bits and TYPE_MASK_CLASS
        val variation = bits and TYPE_MASK_VARIATION
        val text = buildString {
            append(label.orEmpty()).append(' ')
            append(LabelText.humanizeViewId(node.viewIdResourceName).orEmpty()).append(' ')
            append(node.hintText.orEmpty())
        }.lowercase()

        // 1. Explicit sensitive signals always win.
        if (node.isPassword) return if (containsWord(text, otpWords)) FieldType.OTP else if (containsWord(text, pinWords)) FieldType.PIN else FieldType.PASSWORD
        if (cls == TYPE_CLASS_TEXT && variation in setOf(TYPE_TEXT_VARIATION_PASSWORD, TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, TYPE_TEXT_VARIATION_WEB_PASSWORD)) {
            return FieldType.PASSWORD
        }
        if (cls == TYPE_CLASS_NUMBER && variation == TYPE_NUMBER_VARIATION_PASSWORD) return FieldType.PIN
        if (containsWord(text, otpWords)) return FieldType.OTP
        if (containsWord(text, passwordWords)) return FieldType.PASSWORD
        // "PIN" is ambiguous with "PIN code" (postal) – check pincode first.
        if (containsWord(text, pincodeWords)) return FieldType.PINCODE
        if (containsWord(text, pinWords)) return FieldType.PIN

        // Search views (SearchView, SearchAutoComplete, search bars) are search boxes whatever they show.
        if (node.className.orEmpty().contains("Search", ignoreCase = true)) return FieldType.SEARCH

        // 2. Input-type bits.
        when (cls) {
            TYPE_CLASS_PHONE -> return FieldType.PHONE
            TYPE_CLASS_DATETIME -> return FieldType.DATE
            TYPE_CLASS_TEXT -> when (variation) {
                TYPE_TEXT_VARIATION_EMAIL_ADDRESS, TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> return FieldType.EMAIL
                TYPE_TEXT_VARIATION_PERSON_NAME -> return FieldType.NAME
                TYPE_TEXT_VARIATION_POSTAL_ADDRESS -> return FieldType.ADDRESS
                TYPE_TEXT_VARIATION_URI -> return FieldType.URL
            }
        }

        // 3. Keyword heuristics on label / id / hint.
        return when {
            containsWord(text, emailWords) -> FieldType.EMAIL
            containsWord(text, phoneWords) -> FieldType.PHONE
            containsWord(text, dateWords) -> FieldType.DATE
            containsWord(text, addressWords) -> FieldType.ADDRESS
            containsWord(text, amountWords) -> FieldType.AMOUNT
            containsWord(text, searchWords) -> FieldType.SEARCH
            containsWord(text, nameWords) -> FieldType.NAME
            cls == TYPE_CLASS_NUMBER -> if (bits and TYPE_NUMBER_FLAG_DECIMAL != 0) FieldType.AMOUNT else FieldType.NUMBER
            cls == TYPE_CLASS_TEXT && bits and TYPE_TEXT_FLAG_MULTI_LINE != 0 -> FieldType.MULTILINE
            else -> FieldType.TEXT
        }
    }

    /** Word-boundary aware match for Latin keywords; substring match for Devanagari and multi-word phrases. */
    internal fun containsWord(haystack: String, needles: List<String>): Boolean = needles.any { needle ->
        if (needle.any { it.code > 0x7f } || needle.contains(' ')) {
            haystack.contains(needle)
        } else {
            Regex("(^|[^a-z])${Regex.escape(needle)}([^a-z]|$)").containsMatchIn(haystack)
        }
    }
}
