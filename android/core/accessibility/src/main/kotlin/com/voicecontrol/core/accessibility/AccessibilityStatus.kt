package com.voicecontrol.core.accessibility

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings

/** Helpers to check / open the system accessibility setting for VoiceControl. */
object AccessibilityStatus {
    fun isEnabledInSettings(context: Context): Boolean {
        val expected = ComponentName(context, VoiceControlAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
    }

    fun settingsIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
