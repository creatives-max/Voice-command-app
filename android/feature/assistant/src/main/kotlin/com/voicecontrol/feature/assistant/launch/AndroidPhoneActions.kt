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
class AndroidPhoneActions @Inject constructor(@ApplicationContext private val context: Context) : PhoneActions {

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
