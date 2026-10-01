package com.voicecontrol.core.accessibility

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import com.voicecontrol.core.model.Bounds
import com.voicecontrol.core.screen.UiNode

/**
 * [UiNode] backed by a live [AccessibilityNodeInfo]. Properties are read lazily so the parser only
 * pays IPC cost for what it inspects. [path] lets the action executor re-find this exact node.
 */
class AndroidUiNode(val info: AccessibilityNodeInfo) : UiNode {
    override val className: String? get() = info.className?.toString()
    override val viewIdResourceName: String? get() = info.viewIdResourceName
    override val text: String? get() = info.text?.toString()
    override val contentDescription: String? get() = info.contentDescription?.toString()
    override val hintText: String? get() = info.hintText?.toString()
    override val paneTitle: String?
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.paneTitle?.toString() else null
    override val tooltipText: String?
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.tooltipText?.toString() else null
    override val isEditable: Boolean get() = info.isEditable
    override val isPassword: Boolean get() = info.isPassword
    override val isClickable: Boolean get() = info.isClickable
    override val isLongClickable: Boolean get() = info.isLongClickable
    override val isCheckable: Boolean get() = info.isCheckable
    override val isChecked: Boolean get() = info.isChecked
    override val isEnabled: Boolean get() = info.isEnabled
    override val isVisibleToUser: Boolean get() = info.isVisibleToUser
    override val isFocused: Boolean get() = info.isFocused
    override val isScrollable: Boolean get() = info.isScrollable
    override val isShowingHintText: Boolean get() = info.isShowingHintText
    override val inputType: Int get() = info.inputType
    override val maxTextLength: Int get() = info.maxTextLength
    override val boundsInScreen: Bounds by lazy {
        val r = Rect()
        info.getBoundsInScreen(r)
        Bounds(r.left, r.top, r.right, r.bottom)
    }
    override val children: List<UiNode> by lazy {
        (0 until info.childCount).mapNotNull { index -> info.getChild(index)?.let(::AndroidUiNode) }
    }
    override val labeledBy: UiNode? get() = info.labeledBy?.let(::AndroidUiNode)
}
