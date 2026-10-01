package com.voicecontrol.core.model

import kotlinx.serialization.Serializable

/** What kind of interactive element this is. */
@Serializable
enum class ElementKind {
    TEXT_FIELD, BUTTON, CHECKBOX, SWITCH, RADIO, DROPDOWN, LINK;

    val isInput: Boolean get() = this == TEXT_FIELD || this == DROPDOWN
    val isToggle: Boolean get() = this == CHECKBOX || this == SWITCH || this == RADIO
}

/** Semantic type of an input field, used to pick prompts, parsing and validation. */
@Serializable
enum class FieldType {
    TEXT, NAME, EMAIL, PHONE, NUMBER, PASSWORD, OTP, PIN, DATE, ADDRESS, PINCODE, MULTILINE, SEARCH, URL, AMOUNT;

    /** Sensitive types are never read aloud, logged, stored or sent off-device. */
    val isSensitive: Boolean get() = this == PASSWORD || this == OTP || this == PIN
}

@Serializable
data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = left + width / 2
    val centerY: Int get() = top + height / 2
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    fun horizontallyOverlaps(other: Bounds): Boolean = left < other.right && other.left < right
    fun verticallyOverlaps(other: Bounds): Boolean = top < other.bottom && other.top < bottom

    companion object {
        val Zero = Bounds(0, 0, 0, 0)
    }
}

/**
 * One actionable element found on the current screen.
 *
 * [id] is stable across screen refreshes and app restarts as long as the app's layout is the same,
 * so saved flows can refer to it. [value] is always null for sensitive fields.
 */
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
    val isFocused: Boolean = false,
    val isChecked: Boolean? = null,
    val bounds: Bounds = Bounds.Zero,
    val viewId: String? = null,
    val className: String? = null,
    val maxLength: Int? = null,
) {
    val isEmpty: Boolean get() = value.isNullOrBlank()

    /** A copy safe to log or send to the backend: sensitive values removed. */
    fun redacted(): ScreenElement = if (isSensitive) copy(value = null) else this
}

/** Everything VoiceControl understood about the screen at one moment. */
@Serializable
data class ScreenSnapshot(
    val packageName: String,
    val activityName: String? = null,
    val title: String? = null,
    val elements: List<ScreenElement>,
    val isScrollable: Boolean = false,
    val capturedAtMillis: Long = 0L,
    /** Normalized text describing the screen's structure; used for caching and flow matching. */
    val signature: String = "",
) {
    val fields: List<ScreenElement> get() = elements.filter { it.kind.isInput || it.kind.isToggle }
    val textFields: List<ScreenElement> get() = elements.filter { it.kind == ElementKind.TEXT_FIELD }
    val buttons: List<ScreenElement> get() = elements.filter { it.kind == ElementKind.BUTTON || it.kind == ElementKind.LINK }
    val hasReadableElements: Boolean get() = elements.isNotEmpty()

    fun element(id: String): ScreenElement? = elements.firstOrNull { it.id == id }

    fun redacted(): ScreenSnapshot = copy(elements = elements.map { it.redacted() })

    companion object {
        fun empty(packageName: String = "") = ScreenSnapshot(packageName = packageName, elements = emptyList())
    }
}
