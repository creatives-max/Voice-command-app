package com.voicecontrol.feature.assistant.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.Composable
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

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        val metrics = service.resources.displayMetrics
        x = metrics.widthPixels - (88 * metrics.density).toInt()
        y = (metrics.heightPixels * 0.55f).toInt()
    }

    val isShowing: Boolean get() = view != null

    fun show(content: @Composable (onDrag: (Float, Float) -> Unit) -> Unit) {
        if (view != null) return
        val lifecycleOwner = OverlayLifecycleOwner().also { it.onCreate() }
        val composeView = ComposeView(service).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent { content(::moveBy) }
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
        val v = view ?: return
        val metrics = service.resources.displayMetrics
        params.x = (params.x + dx.toInt()).coerceIn(0, metrics.widthPixels - v.width.coerceAtLeast(1))
        params.y = (params.y + dy.toInt()).coerceIn(0, metrics.heightPixels - v.height.coerceAtLeast(1))
        runCatching { windowManager.updateViewLayout(v, params) }
    }
}
