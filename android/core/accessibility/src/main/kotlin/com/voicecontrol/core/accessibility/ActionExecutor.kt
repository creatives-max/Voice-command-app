package com.voicecontrol.core.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import com.voicecontrol.core.model.ActionResult
import com.voicecontrol.core.model.ScreenAction
import com.voicecontrol.core.model.ScrollDirection
import com.voicecontrol.core.model.Screenshot
import com.voicecontrol.core.screen.ScreenParser
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * Performs [ScreenAction]s against the live accessibility tree.
 *
 * Every element action re-parses the current tree so the stable id is resolved to the node that is
 * on screen *now* (nodes go stale quickly). Each action has a fallback chain, e.g. set-text falls
 * back to clipboard paste, click falls back to a clickable ancestor and then to a tap gesture.
 */
class ActionExecutor(
    private val service: AccessibilityService,
    private val parser: ScreenParser,
    private val rootProvider: () -> AccessibilityNodeInfo?,
) {

    suspend fun perform(action: ScreenAction): ActionResult = when (action) {
        is ScreenAction.SetText -> withNode(action.elementId) { setText(it, action.text) }
        is ScreenAction.Click -> withNode(action.elementId) { click(it) }
        is ScreenAction.SetChecked -> withNode(action.elementId) { node ->
            if (node.isChecked == action.checked) ActionResult.Success else click(node)
        }
        is ScreenAction.Focus -> withNode(action.elementId) { node ->
            if (node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) || node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                ActionResult.Success
            } else {
                ActionResult.Failure("Could not focus element")
            }
        }
        is ScreenAction.Scroll -> scroll(action.direction)
        ScreenAction.Back -> if (service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) {
            ActionResult.Success
        } else {
            ActionResult.Failure("Back action rejected")
        }
        is ScreenAction.TapAt -> if (tap(action.x.toFloat(), action.y.toFloat())) ActionResult.Success else ActionResult.Failure("Tap gesture cancelled")
        is ScreenAction.TypeIntoFocused -> typeIntoFocused(action.text)
        is ScreenAction.LaunchApp -> launchApp(action.packageName)
    }

    /** Accessibility services may start activities from the background (system-bound service exemption). */
    private fun launchApp(packageName: String): ActionResult {
        val intent = service.packageManager.getLaunchIntentForPackage(packageName)
            ?: return ActionResult.Failure("App $packageName is not installed")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return runCatching { service.startActivity(intent) }
            .fold({ ActionResult.Success }, { ActionResult.Failure(it.message ?: "Could not open $packageName") })
    }

    private suspend fun withNode(elementId: String, block: suspend (AccessibilityNodeInfo) -> ActionResult): ActionResult {
        val root = rootProvider() ?: return ActionResult.Failure("No readable screen")
        val pkg = root.packageName?.toString() ?: ""
        val parsed = parser.parseWithNodes(AndroidUiNode(root), pkg)
        val node = parsed.nodesById[elementId]?.info
            ?: return ActionResult.Failure("Element $elementId is not on screen")
        return block(node)
    }

    private suspend fun setText(node: AccessibilityNodeInfo, text: String): ActionResult {
        if (!node.isFocused) {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            delay(VERIFY_DELAY_MS)
            node.refresh()
            if (node.isPassword || node.text?.toString() == text) return ActionResult.Success
        }
        return pasteText(node, text)
    }

    /** Fallback for views that ignore ACTION_SET_TEXT (some custom/WebView inputs). */
    private suspend fun pasteText(node: AccessibilityNodeInfo, text: String): ActionResult {
        val clipboard = service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        // Select existing content so paste replaces it.
        val length = node.text?.length ?: 0
        if (length > 0) {
            val selection = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, length)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection)
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("VoiceControl", text))
        val pasted = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        // Do not leave dictated text lingering on the clipboard.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) clipboard.clearPrimaryClip()
        return if (pasted) ActionResult.Success else ActionResult.Failure("Field does not accept text")
    }

    private suspend fun click(node: AccessibilityNodeInfo): ActionResult {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < MAX_ANCESTOR_DEPTH) {
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return ActionResult.Success
            current = current.parent
            depth++
        }
        val rect = Rect().also(node::getBoundsInScreen)
        if (!rect.isEmpty && tap(rect.exactCenterX(), rect.exactCenterY())) return ActionResult.Success
        return ActionResult.Failure("Element could not be clicked")
    }

    private suspend fun scroll(direction: ScrollDirection): ActionResult {
        val root = rootProvider() ?: return ActionResult.Failure("No readable screen")
        val target = largestScrollable(root)
        val action = if (direction == ScrollDirection.DOWN) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        if (target != null && target.performAction(action)) return ActionResult.Success
        // Fallback: swipe gesture across the middle of the screen.
        val metrics = service.resources.displayMetrics
        val x = metrics.widthPixels / 2f
        val (fromY, toY) = if (direction == ScrollDirection.DOWN) {
            metrics.heightPixels * 0.75f to metrics.heightPixels * 0.25f
        } else {
            metrics.heightPixels * 0.25f to metrics.heightPixels * 0.75f
        }
        return if (swipe(x, fromY, x, toY)) ActionResult.Success else ActionResult.Failure("Nothing to scroll")
    }

    private fun largestScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0
        val stack = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        val rect = Rect()
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            if (node.isScrollable && node.isVisibleToUser) {
                node.getBoundsInScreen(rect)
                val area = rect.width() * rect.height()
                if (area > bestArea) {
                    best = node
                    bestArea = area
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(stack::add)
        }
        return best
    }

    private suspend fun typeIntoFocused(text: String): ActionResult {
        val focused = service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: rootProvider()?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return ActionResult.Failure("No focused input")
        if (focused.isPassword) return ActionResult.Failure("Refusing to type into a password field")
        return setText(focused, text)
    }

    private suspend fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return dispatch(GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS))
    }

    private suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        return dispatch(GestureDescription.StrokeDescription(path, 0, SWIPE_DURATION_MS))
    }

    private suspend fun dispatch(stroke: GestureDescription.StrokeDescription): Boolean =
        suspendCancellableCoroutine { cont ->
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            }
            if (!service.dispatchGesture(gesture, callback, null) && cont.isActive) cont.resume(false)
        }

    /** Captures the screen as a downscaled JPEG (API 30+). */
    suspend fun screenshot(): Screenshot? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return takeScreenshotApi30()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun takeScreenshotApi30(): Screenshot? = suspendCancellableCoroutine { cont ->
        service.takeScreenshot(
            Display.DEFAULT_DISPLAY,
            service.mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val shot = runCatching {
                        val hardware = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                        val software = hardware?.copy(Bitmap.Config.ARGB_8888, false)
                        result.hardwareBuffer.close()
                        software?.let(::encodeJpeg)
                    }.getOrNull()
                    if (cont.isActive) cont.resume(shot)
                }

                override fun onFailure(errorCode: Int) {
                    if (cont.isActive) cont.resume(null)
                }
            },
        )
    }

    private fun encodeJpeg(bitmap: Bitmap): Screenshot {
        val scale = minOf(1f, MAX_SCREENSHOT_EDGE.toFloat() / maxOf(bitmap.width, bitmap.height))
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        } else {
            bitmap
        }
        val jpeg = ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            out.toByteArray()
        }
        return Screenshot(jpeg, scaled.width, scaled.height, bitmap.width, bitmap.height)
    }

    companion object {
        private const val VERIFY_DELAY_MS = 120L
        private const val MAX_ANCESTOR_DEPTH = 6
        private const val TAP_DURATION_MS = 60L
        private const val SWIPE_DURATION_MS = 350L
        const val MAX_SCREENSHOT_EDGE = 1280
        private const val JPEG_QUALITY = 70
    }
}
