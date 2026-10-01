package com.voicecontrol.core.screen

import com.voicecontrol.core.model.Bounds

data class FakeUiNode(
    override val className: String? = "android.view.View",
    override val viewIdResourceName: String? = null,
    override val text: String? = null,
    override val contentDescription: String? = null,
    override val hintText: String? = null,
    override val paneTitle: String? = null,
    override val tooltipText: String? = null,
    override val isEditable: Boolean = false,
    override val isPassword: Boolean = false,
    override val isClickable: Boolean = false,
    override val isLongClickable: Boolean = false,
    override val isCheckable: Boolean = false,
    override val isChecked: Boolean = false,
    override val isEnabled: Boolean = true,
    override val isVisibleToUser: Boolean = true,
    override val isFocused: Boolean = false,
    override val isScrollable: Boolean = false,
    override val isShowingHintText: Boolean = false,
    override val inputType: Int = 0,
    override val maxTextLength: Int = -1,
    override val boundsInScreen: Bounds = Bounds(0, 0, 100, 50),
    override val children: List<UiNode> = emptyList(),
    override val labeledBy: UiNode? = null,
) : UiNode

fun editText(
    id: String? = null,
    hint: String? = null,
    text: String? = null,
    inputType: Int = InputTypeBits.TYPE_CLASS_TEXT,
    password: Boolean = false,
    bounds: Bounds = Bounds(0, 0, 1000, 120),
    labeledBy: UiNode? = null,
) = FakeUiNode(
    className = "android.widget.EditText",
    viewIdResourceName = id,
    hintText = hint,
    text = text ?: hint,
    isShowingHintText = text == null && hint != null,
    isEditable = true,
    isClickable = true,
    isPassword = password,
    inputType = inputType,
    boundsInScreen = bounds,
    labeledBy = labeledBy,
)

fun textView(text: String, bounds: Bounds) = FakeUiNode(className = "android.widget.TextView", text = text, boundsInScreen = bounds)

fun button(text: String?, id: String? = null, desc: String? = null, bounds: Bounds = Bounds(0, 2000, 1000, 2120), children: List<UiNode> = emptyList()) =
    FakeUiNode(className = "android.widget.Button", text = text, contentDescription = desc, viewIdResourceName = id, isClickable = true, boundsInScreen = bounds, children = children)

fun root(vararg children: UiNode, title: String? = null) =
    FakeUiNode(className = "android.widget.FrameLayout", paneTitle = title, boundsInScreen = Bounds(0, 0, 1080, 2400), children = children.toList())
