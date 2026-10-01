package com.voicecontrol.app

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

/** Places the launcher's long-press menu opens directly. */
enum class AppShortcut(val id: String, val shortLabel: String, val longLabel: String, val icon: Int) {
    INSIGHTS("insights", "Insights", "Your voice insights", R.drawable.ic_shortcut_insights),
    FLOWS("flows", "My flows", "My saved flows", R.drawable.ic_shortcut_flows),
    MARKETPLACE("marketplace", "Get flows", "Get flows others shared", R.drawable.ic_shortcut_marketplace),
    SETTINGS("settings", "Settings", "VoiceControl settings", R.drawable.ic_shortcut_settings);

    val action: String get() = "$ACTION_PREFIX$id"

    companion object {
        private const val ACTION_PREFIX = "com.voicecontrol.action.OPEN_"

        fun fromAction(action: String?): AppShortcut? = entries.firstOrNull { it.action == action }

        /** Publishes the shortcuts (idempotent; the launcher shows at most four). */
        fun publish(context: Context) {
            val shortcuts = entries.map { s ->
                ShortcutInfoCompat.Builder(context, s.id)
                    .setShortLabel(s.shortLabel)
                    .setLongLabel(s.longLabel)
                    .setIcon(IconCompat.createWithResource(context, s.icon))
                    .setIntent(Intent(context, MainActivity::class.java).setAction(s.action).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                    .build()
            }
            runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts) }
        }
    }
}
