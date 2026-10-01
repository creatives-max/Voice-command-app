package com.voicecontrol.core.screen

import com.voicecontrol.core.model.Bounds

/**
 * Platform-neutral view of one accessibility node.
 *
 * The Android adapter wraps `AccessibilityNodeInfo`; tests use [FakeUiNode]-style implementations.
 * Keeping the parser on this interface makes all screen understanding pure JVM code.
 */
interface UiNode {
    val className: String?
    val viewIdResourceName: String?
    val text: String?
    val contentDescription: String?
    val hintText: String?
    val paneTitle: String?
    val tooltipText: String?
    val isEditable: Boolean
    val isPassword: Boolean
    val isClickable: Boolean
    val isLongClickable: Boolean
    val isCheckable: Boolean
    val isChecked: Boolean
    val isEnabled: Boolean
    val isVisibleToUser: Boolean
    val isFocused: Boolean
    val isScrollable: Boolean
    val isShowingHintText: Boolean
    /** Raw `android.text.InputType` bits, 0 when unknown. */
    val inputType: Int
    val maxTextLength: Int
    val boundsInScreen: Bounds
    val children: List<UiNode>
    /** The node this node is labelled by (`android:labelFor`), if any. */
    val labeledBy: UiNode?
}

/** Constants mirrored from `android.text.InputType` so parsing stays platform-free. */
object InputTypeBits {
    const val TYPE_MASK_CLASS = 0x0000000f
    const val TYPE_MASK_VARIATION = 0x00000ff0
    const val TYPE_MASK_FLAGS = 0x00fff000

    const val TYPE_CLASS_TEXT = 0x00000001
    const val TYPE_CLASS_NUMBER = 0x00000002
    const val TYPE_CLASS_PHONE = 0x00000003
    const val TYPE_CLASS_DATETIME = 0x00000004

    const val TYPE_TEXT_VARIATION_URI = 0x00000010
    const val TYPE_TEXT_VARIATION_EMAIL_ADDRESS = 0x00000020
    const val TYPE_TEXT_VARIATION_PERSON_NAME = 0x00000060
    const val TYPE_TEXT_VARIATION_POSTAL_ADDRESS = 0x00000070
    const val TYPE_TEXT_VARIATION_PASSWORD = 0x00000080
    const val TYPE_TEXT_VARIATION_VISIBLE_PASSWORD = 0x00000090
    const val TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS = 0x000000d0
    const val TYPE_TEXT_VARIATION_WEB_PASSWORD = 0x000000e0
    const val TYPE_TEXT_FLAG_MULTI_LINE = 0x00020000
    const val TYPE_NUMBER_VARIATION_PASSWORD = 0x00000010
    const val TYPE_NUMBER_FLAG_DECIMAL = 0x00002000
}
