package com.voicecontrol.core.engine.port

import com.voicecontrol.core.nlp.SearchPlace
import com.voicecontrol.core.nlp.SettingsPage
import com.voicecontrol.core.nlp.VolumeChange

/** A contact looked up by name ("call Rahul"). */
sealed interface ContactResult {
    data class Found(val name: String, val number: String) : ContactResult
    data object NotFound : ContactResult
    /** Contacts permission is not granted (the user was asked for it). */
    data object NoPermission : ContactResult
}

enum class DialResult {
    /** The call was started. */
    CALLING,
    /** The number is in the dialer; the user presses call (no call permission). */
    DIALED,
    FAILED,
}

/** Everyday phone jobs for the personal assistant: alarms, timers, searches, calls and messages. */
interface PhoneActions {
    suspend fun setAlarm(hour: Int, minute: Int): Boolean
    suspend fun setTimer(seconds: Int): Boolean
    suspend fun search(place: SearchPlace, query: String): Boolean
    suspend fun findContact(name: String): ContactResult
    suspend fun call(number: String): DialResult
    /** Opens a WhatsApp chat with [number], with [text] typed in (the user or "send" sends it). */
    suspend fun whatsapp(number: String, text: String?): Boolean
    /** An alarm at that time whose label is what to remember. */
    suspend fun setReminder(hour: Int, minute: Int, text: String?): Boolean = false
    suspend fun torch(on: Boolean): Boolean = false
    suspend fun volume(change: VolumeChange): Boolean = false
    suspend fun battery(): BatteryInfo? = null
    suspend fun openSettings(page: SettingsPage): Boolean = false
    /** Notifications seen recently, newest first (kept in memory on the phone only). */
    suspend fun notifications(): List<NotificationInfo> = emptyList()
}

data class BatteryInfo(val percent: Int, val charging: Boolean)

data class NotificationInfo(val app: String, val title: String?, val text: String)
