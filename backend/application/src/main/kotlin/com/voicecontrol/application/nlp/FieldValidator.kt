// Mirrors android/core/nlp so the offline "rules" provider understands speech exactly like the phone.
package com.voicecontrol.application.nlp

import com.voicecontrol.domain.ai.FieldType

/**
 * Validates a value against flow rules edited in the dashboard.
 *
 * Rule syntax (one per entry): `required`, `email`, `phone`, `pincode`, `digits:N`, `min:N`, `max:N`,
 * `regex:<pattern>`, `oneOf:a|b|c`. Field types add implicit checks (e.g. EMAIL must look like an email).
 */
object FieldValidator {

    sealed interface Result {
        data object Valid : Result
        data class Invalid(val message: String) : Result
    }

    fun validate(value: String, type: FieldType?, rules: List<String>): Result {
        val v = value.trim()
        val explicit = rules.map { it.trim() }.filter { it.isNotEmpty() }
        if (v.isEmpty()) {
            return if ("required" in explicit) Result.Invalid("This field is required") else Result.Valid
        }
        val implicit = when (type) {
            FieldType.EMAIL -> listOf("email")
            FieldType.PHONE -> listOf("phone")
            FieldType.PINCODE -> listOf("pincode")
            else -> emptyList()
        }
        for (rule in implicit + explicit) {
            val name = rule.substringBefore(':').trim().lowercase()
            val arg = rule.substringAfter(':', "").trim()
            val error: String? = when (name) {
                "required" -> null
                "email" -> if (EmailNormalizer.isValid(v.lowercase())) null else "That doesn't look like an email address"
                "phone" -> if (v.filter(Char::isDigit).length in 10..13) null else "Please say a 10 digit mobile number"
                "pincode" -> if (Regex("^\\d{6}$").matches(v)) null else "PIN code must be 6 digits"
                "digits" -> arg.toIntOrNull()?.let { n -> if (v.length == n && v.all(Char::isDigit)) null else "Please say exactly $n digits" }
                "min" -> arg.toIntOrNull()?.let { n -> if (v.length >= n) null else "Please say at least $n characters" }
                "max" -> arg.toIntOrNull()?.let { n -> if (v.length <= n) null else "That is longer than $n characters" }
                "regex" -> runCatching { Regex(arg) }.getOrNull()?.let { r -> if (r.matches(v)) null else "That value is not in the expected format" }
                "oneof" -> arg.split('|').map { it.trim().lowercase() }.let { options ->
                    if (v.lowercase() in options) null else "Please choose one of: ${options.joinToString(", ")}"
                }
                else -> null
            }
            if (error != null) return Result.Invalid(error)
        }
        return Result.Valid
    }
}
