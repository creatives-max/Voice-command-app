package com.voicecontrol.core.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Plug-in point for features that need the live accessibility service (e.g. the overlay mic button).
 * Implementations are contributed with Hilt `@IntoSet` so `core:accessibility` never depends on features.
 */
interface ServiceListener {
    fun onServiceConnected(service: AccessibilityService) {}
    fun onForegroundAppChanged(packageName: String) {}
    fun onAccessibilityEvent(event: AccessibilityEvent) {}
    fun onServiceDisconnected() {}
}
