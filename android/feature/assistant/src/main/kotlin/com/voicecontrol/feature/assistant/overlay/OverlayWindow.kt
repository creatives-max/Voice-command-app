package com.voicecontrol.feature.assistant.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * A draggable Compose window drawn above other apps as an accessibility overlay.
 * TYPE_ACCESSIBILITY_OVERLAY needs no SYSTEM_ALERT_WINDOW permission and never steals focus,
 * so the app underneath keeps its keyboard and input focus.
 */
internal class OverlayWindow(private val service: AccessibilityService) {
    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: ComposeView? = null
    private var owner: OverlayLifecycleOwner? = null

    private val micSize = (MIC_DP * service.resources.displayMetrics.density).toInt()

    /** Top-left corner of the mic bubble on screen; the window is placed around it (see [OverlayPlacement]). */
    private var micX = service.resources.displayMetrics.widthPixels - micSize - (24 * service.resources.displayMetrics.density).toInt()
    private var micY = (service.resources.displayMetrics.heightPixels * 0.55f).toInt()

    /** Whether the caption and panel line up with the mic's left edge (mic on the left half of the screen). */
    private val alignStart = mutableStateOf(false)

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = micX
        y = micY
    }

    val isShowing: Boolean get() = view != null

    fun show(content: @Composable (onDrag: (Float, Float) -> Unit, alignStart: Boolean) -> Unit) {
        if (view != null) return
        val lifecycleOwner = OverlayLifecycleOwner().also { it.onCreate() }
        val composeView = ComposeView(service).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent { content(::moveBy, alignStart.value) }
            // The caption or the button panel opening changes the window's size: keep the mic in place.
            addOnLayoutChangeListener { v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) v.post { place() }
            }
        }
        windowManager.addView(composeView, params)
        view = composeView
        owner = lifecycleOwner
    }

    fun hide() {
        view?.let { runCatching { windowManager.removeView(it) } }
        owner?.onDestroy()
        view = null
        owner = null
    }

    private fun moveBy(dx: Float, dy: Float) {
        val metrics = service.resources.displayMetrics
        val (x, y) = OverlayPlacement.clampMic(micX + dx.toInt(), micY + dy.toInt(), micSize, metrics.widthPixels, metrics.heightPixels)
        micX = x
        micY = y
        place()
    }

    /** Places the window so the mic stays where the user put it and nothing goes off screen. */
    private fun place() {
        val v = view ?: return
        val metrics = service.resources.displayMetrics
        val p = OverlayPlacement.place(
            micX, micY, micSize,
            v.width.coerceAtLeast(micSize), v.height.coerceAtLeast(micSize),
            metrics.widthPixels, metrics.heightPixels,
        )
        alignStart.value = p.alignStart
        if (params.x == p.x && params.y == p.y) return
        params.x = p.x
        params.y = p.y
        runCatching { windowManager.updateViewLayout(v, params) }
    }

    private companion object {
        /** Size of the mic bubble in OverlayContent. */
        const val MIC_DP = 64
    }
}
