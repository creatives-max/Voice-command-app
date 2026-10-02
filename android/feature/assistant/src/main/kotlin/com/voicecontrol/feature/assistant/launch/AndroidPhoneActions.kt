package com.voicecontrol.feature.assistant.launch

import android.Manifest
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.voicecontrol.core.engine.port.ContactResult
import com.voicecontrol.core.engine.port.DialResult
import com.voicecontrol.core.engine.port.PhoneActions
import com.voicecontrol.core.nlp.SearchPlace
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Alarms, timers, searches, calls and WhatsApp messages through the phone's own apps. VoiceControl may
 * start them while another app is in front because it holds the overlay permission.
 */
@Singleton
class AndroidPhoneActions @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bridge: com.voicecontrol.core.accessibility.AccessibilityBridge,
) : PhoneActions {

    override suspend fun setAlarm(hour: Int, minute: Int): Boolean = start(
        Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true),
    )

    override suspend fun setTimer(seconds: Int): Boolean = start(
        Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true),
    )

    override suspend fun search(place: SearchPlace, query: String): Boolean {
        val q = Uri.encode(query)
        return when (place) {
            SearchPlace.YOUTUBE ->
                start(Intent(Intent.ACTION_SEARCH).setPackage(YOUTUBE).putExtra("query", query)) ||
                    start(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$q")))
            SearchPlace.MAPS ->
                start(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$q")))
            SearchPlace.WEB ->
                start(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query)) ||
                    start(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$q")))
        }
    }

    override suspend fun findContact(name: String): ContactResult = withContext(Dispatchers.IO) {
        if (!granted(Manifest.permission.READ_CONTACTS)) {
            askPermissions()
            return@withContext ContactResult.NoPermission
        }
        val uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(name))
        val columns = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
        runCatching {
            context.contentResolver.query(uri, columns, null, null, null)?.use { c ->
                if (c.moveToFirst()) ContactResult.Found(c.getString(0) ?: name, c.getString(1).orEmpty()) else null
            }
        }.getOrNull()?.takeIf { it.number.isNotBlank() } ?: ContactResult.NotFound
    }

    override suspend fun call(number: String): DialResult {
        val tel = Uri.fromParts("tel", number, null)
        if (granted(Manifest.permission.CALL_PHONE) && start(Intent(Intent.ACTION_CALL, tel))) return DialResult.CALLING
        return if (start(Intent(Intent.ACTION_DIAL, tel))) DialResult.DIALED else DialResult.FAILED
    }

    override suspend fun whatsapp(number: String, text: String?): Boolean {
        val digits = internationalDigits(number)
        val link = "https://wa.me/$digits" + (text?.let { "?text=" + Uri.encode(it) } ?: "")
        return start(Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(WHATSAPP)) ||
            start(Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(WHATSAPP_BUSINESS)) ||
            start(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
    }

    override suspend fun setReminder(hour: Int, minute: Int, text: String?): Boolean = start(
        Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, text?.take(60) ?: "VoiceControl")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true),
    )

    override suspend fun torch(on: Boolean): Boolean = withContext(Dispatchers.Main) {
        runCatching {
            val camera = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val id = camera.cameraIdList.firstOrNull { cid ->
                camera.getCameraCharacteristics(cid).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return@runCatching false
            camera.setTorchMode(id, on)
            true
        }.getOrDefault(false)
    }

    override suspend fun volume(change: com.voicecontrol.core.nlp.VolumeChange): Boolean = withContext(Dispatchers.Main) {
        runCatching {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val stream = android.media.AudioManager.STREAM_MUSIC
            val show = android.media.AudioManager.FLAG_SHOW_UI
            when (change) {
                com.voicecontrol.core.nlp.VolumeChange.UP -> repeat(2) { audio.adjustStreamVolume(stream, android.media.AudioManager.ADJUST_RAISE, show) }
                com.voicecontrol.core.nlp.VolumeChange.DOWN -> repeat(2) { audio.adjustStreamVolume(stream, android.media.AudioManager.ADJUST_LOWER, show) }
                com.voicecontrol.core.nlp.VolumeChange.MAX -> audio.setStreamVolume(stream, audio.getStreamMaxVolume(stream), show)
                // Silent mode needs Do Not Disturb access; vibrate is allowed for every app.
                com.voicecontrol.core.nlp.VolumeChange.MUTE -> audio.ringerMode = android.media.AudioManager.RINGER_MODE_VIBRATE
            }
            true
        }.getOrDefault(false)
    }

    override suspend fun battery(): com.voicecontrol.core.engine.port.BatteryInfo? = runCatching {
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        val percent = manager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (percent !in 0..100) return@runCatching null
        com.voicecontrol.core.engine.port.BatteryInfo(percent, manager.isCharging)
    }.getOrNull()

    override suspend fun openSettings(page: com.voicecontrol.core.nlp.SettingsPage): Boolean {
        val q = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q
        val action = when (page) {
            com.voicecontrol.core.nlp.SettingsPage.WIFI -> if (q) android.provider.Settings.Panel.ACTION_WIFI else android.provider.Settings.ACTION_WIFI_SETTINGS
            com.voicecontrol.core.nlp.SettingsPage.INTERNET -> if (q) android.provider.Settings.Panel.ACTION_INTERNET_CONNECTIVITY else android.provider.Settings.ACTION_WIRELESS_SETTINGS
            com.voicecontrol.core.nlp.SettingsPage.BLUETOOTH -> android.provider.Settings.ACTION_BLUETOOTH_SETTINGS
            com.voicecontrol.core.nlp.SettingsPage.DISPLAY -> android.provider.Settings.ACTION_DISPLAY_SETTINGS
            com.voicecontrol.core.nlp.SettingsPage.SOUND -> android.provider.Settings.ACTION_SOUND_SETTINGS
            com.voicecontrol.core.nlp.SettingsPage.BATTERY -> Intent.ACTION_POWER_USAGE_SUMMARY
            com.voicecontrol.core.nlp.SettingsPage.LOCATION -> android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS
            com.voicecontrol.core.nlp.SettingsPage.AIRPLANE -> android.provider.Settings.ACTION_AIRPLANE_MODE_SETTINGS
            com.voicecontrol.core.nlp.SettingsPage.DO_NOT_DISTURB -> "android.settings.ZEN_MODE_SETTINGS"
            com.voicecontrol.core.nlp.SettingsPage.MAIN -> android.provider.Settings.ACTION_SETTINGS
        }
        return start(Intent(action)) || start(Intent(android.provider.Settings.ACTION_SETTINGS))
    }

    override suspend fun notifications(): List<com.voicecontrol.core.engine.port.NotificationInfo> =
        bridge.recentNotifications().map { com.voicecontrol.core.engine.port.NotificationInfo(it.app, it.title, it.text) }

    override suspend fun system(action: com.voicecontrol.core.nlp.SystemAction): Boolean = withContext(Dispatchers.Main) {
        val p = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P
        val id = when (action) {
            com.voicecontrol.core.nlp.SystemAction.HOME -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
            com.voicecontrol.core.nlp.SystemAction.RECENTS -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
            com.voicecontrol.core.nlp.SystemAction.NOTIFICATIONS -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            com.voicecontrol.core.nlp.SystemAction.QUICK_SETTINGS -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            com.voicecontrol.core.nlp.SystemAction.POWER_MENU -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
            com.voicecontrol.core.nlp.SystemAction.LOCK ->
                if (p) android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN else return@withContext false
            com.voicecontrol.core.nlp.SystemAction.SCREENSHOT ->
                if (p) android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT else return@withContext false
        }
        bridge.performGlobal(id)
    }

    override suspend fun camera(video: Boolean, selfie: Boolean): Boolean {
        val intent = Intent(if (video) android.provider.MediaStore.INTENT_ACTION_VIDEO_CAMERA else android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        // Most camera apps honour these to open the front camera.
        if (selfie) {
            intent.putExtra("android.intent.extras.CAMERA_FACING", 1)
                .putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
                .putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
        }
        return start(intent)
    }

    override suspend fun brightness(change: com.voicecontrol.core.nlp.VolumeChange): com.voicecontrol.core.engine.port.ControlResult {
        if (!android.provider.Settings.System.canWrite(context)) {
            val asked = start(Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + context.packageName)))
            return if (asked) com.voicecontrol.core.engine.port.ControlResult.ASKED_PERMISSION else com.voicecontrol.core.engine.port.ControlResult.FAILED
        }
        return runCatching {
            val resolver = context.contentResolver
            android.provider.Settings.System.putInt(
                resolver,
                android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            )
            val now = android.provider.Settings.System.getInt(resolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, 128)
            val next = when (change) {
                com.voicecontrol.core.nlp.VolumeChange.UP -> now + 64
                com.voicecontrol.core.nlp.VolumeChange.DOWN -> now - 64
                com.voicecontrol.core.nlp.VolumeChange.MAX -> 255
                com.voicecontrol.core.nlp.VolumeChange.MUTE -> 10
            }.coerceIn(10, 255)
            android.provider.Settings.System.putInt(resolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, next)
            com.voicecontrol.core.engine.port.ControlResult.DONE
        }.getOrDefault(com.voicecontrol.core.engine.port.ControlResult.FAILED)
    }

    override suspend fun media(key: com.voicecontrol.core.nlp.MediaKey): Boolean = withContext(Dispatchers.Main) {
        runCatching {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val code = when (key) {
                com.voicecontrol.core.nlp.MediaKey.PLAY -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY
                com.voicecontrol.core.nlp.MediaKey.PAUSE -> android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
                com.voicecontrol.core.nlp.MediaKey.NEXT -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT
                com.voicecontrol.core.nlp.MediaKey.PREVIOUS -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
            }
            audio.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))
            audio.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))
            true
        }.getOrDefault(false)
    }

    private suspend fun askPermissions() {
        start(Intent(context, PhonePermissionActivity::class.java))
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private suspend fun start(intent: Intent): Boolean = withContext(Dispatchers.Main) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    companion object {
        const val YOUTUBE = "com.google.android.youtube"
        const val WHATSAPP = "com.whatsapp"
        const val WHATSAPP_BUSINESS = "com.whatsapp.w4b"

        /** wa.me needs the country code: a 10-digit Indian mobile number gets 91. */
        fun internationalDigits(number: String): String {
            val digits = number.filter(Char::isDigit).trimStart('0')
            return if (digits.length == 10 && digits.first() in '6'..'9') "91$digits" else digits
        }
    }
}
