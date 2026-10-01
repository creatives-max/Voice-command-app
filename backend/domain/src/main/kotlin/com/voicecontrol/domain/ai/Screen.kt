package com.voicecontrol.domain.ai

import kotlinx.serialization.Serializable

@Serializable
enum class ElementKind { TEXT_FIELD, BUTTON, CHECKBOX, SWITCH, RADIO, DROPDOWN, LINK }

@Serializable
enum class FieldType {
    TEXT, NAME, EMAIL, PHONE, NUMBER, PASSWORD, OTP, PIN, DATE, ADDRESS, PINCODE, MULTILINE, SEARCH, URL, AMOUNT;

    val isSensitive: Boolean get() = this == PASSWORD || this == OTP || this == PIN
}

@Serializable
enum class Language { ENGLISH, HINDI, HINGLISH }

/** One element as sent by the phone (values of sensitive fields are always null). */
@Serializable
data class ScreenElement(
    val id: String,
    val kind: ElementKind,
    val label: String,
    val fieldType: FieldType? = null,
    val hint: String? = null,
    val value: String? = null,
    val isSensitive: Boolean = false,
    val isEnabled: Boolean = true,
    val isChecked: Boolean? = null,
) {
    val sensitive: Boolean get() = isSensitive || fieldType?.isSensitive == true
}

@Serializable
data class ScreenContext(
    val packageName: String,
    val activityName: String? = null,
    val title: String? = null,
    val elements: List<ScreenElement>,
    val signature: String = "",
) {
    fun element(id: String?): ScreenElement? = id?.let { wanted -> elements.firstOrNull { it.id == wanted } }

    /** Defense in depth: strip any value of a sensitive field even if a client sent one. */
    fun redacted(): ScreenContext = copy(elements = elements.map { if (it.sensitive) it.copy(value = null) else it })
}
