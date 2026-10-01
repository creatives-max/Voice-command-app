package com.voicecontrol.feature.assistant.launch

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import com.voicecontrol.core.accessibility.AccessibilityBridge
import com.voicecontrol.feature.assistant.AssistantController
import com.voicecontrol.feature.assistant.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** What a home-screen widget or quick-settings tap should do. */
enum class QuickStartAction { TOGGLE_SESSION, OPEN_APP }

object QuickStart {
    /** Sessions need the accessibility service and the microphone; otherwise the app opens to set them up. */
    fun decide(serviceConnected: Boolean, micGranted: Boolean): QuickStartAction =
        if (serviceConnected && micGranted) QuickStartAction.TOGGLE_SESSION else QuickStartAction.OPEN_APP

    fun micGranted(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun openAppIntent(context: Context): Intent? =
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/**
 * Invisible activity started by the widget: starts (or stops) a voice session over the app that was in
 * front, or opens VoiceControl when the service or microphone still needs to be set up.
 */
@AndroidEntryPoint
class StartVoiceActivity : ComponentActivity() {
    @Inject lateinit var controller: AssistantController
    @Inject lateinit var bridge: AccessibilityBridge

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (QuickStart.decide(bridge.isConnected.value, QuickStart.micGranted(this))) {
            QuickStartAction.TOGGLE_SESSION -> controller.onMicTap()
            QuickStartAction.OPEN_APP -> QuickStart.openAppIntent(this)?.let(::startActivity)
        }
        finish()
    }
}

/** Home-screen widget: one tap to talk. */
class VoiceWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val tap = PendingIntent.getActivity(
            context, 0,
            Intent(context, StartVoiceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        appWidgetIds.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.vc_widget_voice).apply {
                setOnClickPendingIntent(R.id.vc_widget_root, tap)
            }
            manager.updateAppWidget(id, views)
        }
    }
}

/** Quick-settings tile: starts or stops a voice session from anywhere. */
@AndroidEntryPoint
class VoiceTileService : TileService() {
    @Inject lateinit var controller: AssistantController
    @Inject lateinit var bridge: AccessibilityBridge

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        when (QuickStart.decide(bridge.isConnected.value, QuickStart.micGranted(this))) {
            QuickStartAction.TOGGLE_SESSION -> {
                controller.onMicTap()
                refresh()
            }
            QuickStartAction.OPEN_APP -> openApp()
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = QuickStart.openAppIntent(this) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            startActivityAndCollapse(intent)
        }
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val active = controller.sessionActive
        tile.state = when {
            !bridge.isConnected.value -> Tile.STATE_INACTIVE
            active -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = getString(R.string.vc_tile_label)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(if (active) R.string.vc_tile_listening else if (bridge.isConnected.value) R.string.vc_tile_ready else R.string.vc_tile_setup)
        }
        tile.icon = Icon.createWithResource(this, R.drawable.vc_ic_mic)
        tile.updateTile()
    }
}
