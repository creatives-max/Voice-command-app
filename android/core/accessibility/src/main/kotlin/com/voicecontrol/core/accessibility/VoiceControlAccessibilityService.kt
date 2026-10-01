package com.voicecontrol.core.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The OS-bound accessibility service. Deliberately thin: it forwards lifecycle and events to
 * [AccessibilityBridge] and to feature [ServiceListener]s; all logic lives elsewhere.
 */
@AndroidEntryPoint
class VoiceControlAccessibilityService : AccessibilityService() {

    @Inject lateinit var bridge: AccessibilityBridge

    @Inject lateinit var listeners: Set<@JvmSuppressWildcards ServiceListener>

    override fun onServiceConnected() {
        super.onServiceConnected()
        bridge.attach(this)
        listeners.forEach { it.onServiceConnected(this) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        bridge.onEvent(event)
        listeners.forEach { it.onAccessibilityEvent(event) }
    }

    internal fun notifyForegroundChanged(packageName: String) {
        listeners.forEach { it.onForegroundAppChanged(packageName) }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        listeners.forEach { it.onServiceDisconnected() }
        bridge.detach()
        super.onDestroy()
    }
}
