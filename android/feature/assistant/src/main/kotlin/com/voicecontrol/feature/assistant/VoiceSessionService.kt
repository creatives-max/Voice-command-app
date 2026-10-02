package com.voicecontrol.feature.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Foreground service (type microphone) that runs while a voice session is active or while the wake
 * phrase is being listened for, so the microphone stays available when another app is in front and
 * the user always sees that VoiceControl is listening.
 */
@AndroidEntryPoint
class VoiceSessionService : Service() {

    @Inject lateinit var controller: AssistantController
    @Inject lateinit var microphone: MicrophoneForeground

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            if (controller.sessionActive) controller.onMicTap()
            microphone.pauseWakeWord()
            stopSelf()
            return START_NOT_STICKY
        }
        val wakePhrase = intent?.getStringExtra(EXTRA_WAKE_PHRASE)
        ensureChannel()
        val stopIntent = PendingIntent.getService(
            this, 0, Intent(this, VoiceSessionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(if (wakePhrase != null) "Waiting for “$wakePhrase”" else "VoiceControl is listening")
            .setContentText(if (wakePhrase != null) "Say your wake phrase to start. Tap Stop to pause until you open VoiceControl." else "Tap Stop or the mic bubble to end the session")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Stop", stopIntent)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type) }
            .onSuccess { isRunning = true }
            .onFailure {
                // Android 14+ refuses a microphone service started from the background; it works again once
                // VoiceControl is opened (MainActivity restarts it from the foreground).
                startFailed = true
                stopSelf()
            }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Voice session", NotificationManager.IMPORTANCE_LOW))
        }
    }

    companion object {
        private const val CHANNEL_ID = "voice_session"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_STOP = "com.voicecontrol.action.STOP_SESSION"

        private const val EXTRA_WAKE_PHRASE = "wake_phrase"

        /** The service holds the microphone in the foreground right now. */
        @Volatile var isRunning = false
            private set

        /** The last start was refused (started from the background on Android 14+). */
        @Volatile var startFailed = false
            private set

        /**
         * Starts (or updates) the service; with [wakePhrase] it shows that the wake phrase is being listened for.
         * A running service is only updated, which is allowed from the background (a new start is not).
         */
        fun start(context: Context, wakePhrase: String? = null) {
            val intent = Intent(context, VoiceSessionService::class.java).apply { wakePhrase?.let { putExtra(EXTRA_WAKE_PHRASE, it) } }
            startFailed = false
            runCatching { if (isRunning) context.startService(intent) else ContextCompat.startForegroundService(context, intent) }
                .onFailure { startFailed = true }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VoiceSessionService::class.java))
        }
    }
}
