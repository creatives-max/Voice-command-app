package com.voicecontrol.core.engine

import com.voicecontrol.core.engine.port.ContactResult
import com.voicecontrol.core.engine.port.DialResult
import com.voicecontrol.core.engine.port.PhoneActions
import com.voicecontrol.core.model.Language
import com.voicecontrol.core.model.ScreenElement
import com.voicecontrol.core.nlp.PhoneTask
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The personal-assistant jobs ([PhoneTask]): answers the time and date itself and hands alarms, timers,
 * searches, calls and WhatsApp messages to the phone. Calls and messages end on the app's own screen,
 * where saying "call" or "send" presses its button ([spokenAlias]).
 */
internal class PersonalTasks(
    private val phone: PhoneActions?,
    private val clock: () -> Long,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    enum class Result {
        /** Something opened or changed on screen: read the screen again. */
        MOVED,
        /** Answered by voice; the same screen is still showing. */
        ANSWERED,
        /** A WhatsApp chat opened with the message typed in; the caller confirms and presses Send. */
        MESSAGE_READY,
    }

    /** The last person called, messaged or looked up, for "usko call karo" / "call him". */
    var lastPerson: String? = null
        private set

    /** Who the last [Result.MESSAGE_READY] message is for. */
    var messageTo: String = ""
        private set

    suspend fun run(
        task: PhoneTask,
        phrases: Phrases,
        language: Language,
        say: suspend (String) -> Unit,
        ask: suspend (String) -> String?,
    ): Result {
        val locale = Locale.forLanguageTag(if (language == Language.HINGLISH) "en-IN" else language.voiceTag)
        when (task) {
            PhoneTask.TimeNow -> {
                val now = Instant.ofEpochMilli(clock()).atZone(zone()).toLocalTime()
                say(phrases.timeNow(spokenTime(now, locale)))
                return Result.ANSWERED
            }
            PhoneTask.DateToday -> {
                val today = Instant.ofEpochMilli(clock()).atZone(zone()).toLocalDate()
                say(phrases.today(today.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", locale))))
                return Result.ANSWERED
            }
            PhoneTask.Capabilities -> {
                say(phrases.capabilities())
                return Result.ANSWERED
            }
            is PhoneTask.Calculate -> {
                say(phrases.answer(spokenNumber(task.result)))
                return Result.ANSWERED
            }
            else -> Unit
        }
        val actions = phone ?: run {
            say(phrases.taskFailed())
            return Result.ANSWERED
        }
        when (task) {
            is PhoneTask.Alarm -> {
                val ok = actions.setAlarm(task.hour, task.minute)
                say(if (ok) phrases.alarmSet(spokenTime(LocalTime.of(task.hour, task.minute), locale)) else phrases.taskFailed())
            }
            is PhoneTask.Timer -> {
                val ok = actions.setTimer(task.seconds)
                say(if (ok) phrases.timerSet(phrases.duration(task.seconds)) else phrases.taskFailed())
            }
            is PhoneTask.Search -> {
                say(phrases.searching(task.query))
                if (!actions.search(task.place, task.query)) say(phrases.taskFailed())
            }
            is PhoneTask.Call -> {
                val (name, number) = contact(actions, task.who, phrases, say) ?: return missed
                when (actions.call(number)) {
                    DialResult.CALLING -> say(phrases.calling(name))
                    DialResult.DIALED -> say(phrases.dialed(name))
                    DialResult.FAILED -> say(phrases.taskFailed())
                }
            }
            is PhoneTask.Message -> {
                val (name, number) = contact(actions, task.who, phrases, say) ?: return missed
                val text = task.text ?: ask(phrases.askMessage(name))?.trim()?.takeIf { it.isNotEmpty() }
                if (actions.whatsapp(number, text)) {
                    messageTo = name
                    return Result.MESSAGE_READY
                }
                say(phrases.taskFailed())
            }
            is PhoneTask.Reminder -> {
                val ok = actions.setReminder(task.hour, task.minute, task.text)
                say(if (ok) phrases.reminderSet(spokenTime(LocalTime.of(task.hour, task.minute), locale), task.text) else phrases.taskFailed())
            }
            is PhoneTask.Torch -> {
                say(if (actions.torch(task.on)) phrases.torch(task.on) else phrases.taskFailed())
                return Result.ANSWERED
            }
            is PhoneTask.Volume -> {
                say(if (actions.volume(task.change)) phrases.volumeChanged(task.change) else phrases.taskFailed())
                return Result.ANSWERED
            }
            PhoneTask.Battery -> {
                val info = actions.battery()
                say(if (info != null) phrases.battery(info.percent, info.charging) else phrases.taskFailed())
                return Result.ANSWERED
            }
            is PhoneTask.OpenSettings -> {
                if (!actions.openSettings(task.page)) {
                    say(phrases.taskFailed())
                    return Result.ANSWERED
                }
                say(phrases.openingSettings())
            }
            PhoneTask.ReadNotifications -> {
                val latest = actions.notifications().take(MAX_READ_NOTIFICATIONS)
                if (latest.isEmpty()) {
                    say(phrases.noNotifications())
                } else {
                    say(phrases.notificationsIntro(latest.size))
                    latest.forEach { n -> say(phrases.notificationLine(n.app, n.title, n.text.take(MAX_NOTIFICATION_CHARS))) }
                }
                return Result.ANSWERED
            }
            is PhoneTask.System -> {
                // Say it first: locking or the power menu leaves nothing to speak over.
                val before = task.action == com.voicecontrol.core.nlp.SystemAction.LOCK
                if (before) say(phrases.systemDone(task.action))
                if (!actions.system(task.action)) {
                    say(phrases.taskFailed())
                    return Result.ANSWERED
                }
                if (!before) say(phrases.systemDone(task.action))
            }
            is PhoneTask.Camera -> {
                say(phrases.openingCamera())
                if (!actions.camera(task.video, task.selfie)) say(phrases.taskFailed())
            }
            is PhoneTask.Brightness -> when (actions.brightness(task.change)) {
                com.voicecontrol.core.engine.port.ControlResult.DONE -> {
                    say(phrases.brightnessChanged())
                    return Result.ANSWERED
                }
                com.voicecontrol.core.engine.port.ControlResult.ASKED_PERMISSION -> say(phrases.needSettingsPermission())
                com.voicecontrol.core.engine.port.ControlResult.FAILED -> {
                    say(phrases.taskFailed())
                    return Result.ANSWERED
                }
            }
            is PhoneTask.Media -> {
                if (!actions.media(task.key)) say(phrases.taskFailed())
                return Result.ANSWERED
            }
            PhoneTask.Emergency -> {
                val saved = actions.emergencyContact()
                val found = saved?.let { actions.findContact(it) as? ContactResult.Found }
                if (found != null) {
                    say(phrases.callingForHelp(found.name))
                    if (actions.call(found.number) == DialResult.FAILED) say(phrases.taskFailed())
                } else {
                    // Never call the emergency number on a misheard word: ask first.
                    val answer = ask(phrases.askCallEmergencyNumber()).orEmpty().lowercase().split(Regex("[\\s,.!?।]+"))
                    if (answer.any { it in yesWords }) {
                        say(phrases.calling(EMERGENCY_NUMBER))
                        actions.call(EMERGENCY_NUMBER)
                    } else {
                        say(phrases.emergencyHint())
                        return Result.ANSWERED
                    }
                }
            }
            is PhoneTask.SetEmergencyContact -> {
                when (val found = actions.findContact(task.who)) {
                    is ContactResult.Found -> say(if (actions.setEmergencyContact(found.name)) phrases.emergencySaved(found.name) else phrases.taskFailed())
                    ContactResult.NotFound -> say(phrases.contactNotFound(task.who))
                    ContactResult.NoPermission -> {
                        say(phrases.needContacts())
                        return Result.MOVED
                    }
                }
                return Result.ANSWERED
            }
            is PhoneTask.ContactNumber -> {
                val who = person(task.who, phrases, say) ?: return Result.ANSWERED
                when (val found = actions.findContact(who)) {
                    is ContactResult.Found -> {
                        lastPerson = who
                        say(phrases.contactNumberIs(found.name, found.number.filter { it.isDigit() || it == '+' }.toList().joinToString(" ")))
                    }
                    ContactResult.NotFound -> say(phrases.contactNotFound(who))
                    ContactResult.NoPermission -> {
                        say(phrases.needContacts())
                        return Result.MOVED
                    }
                }
                return Result.ANSWERED
            }
            is PhoneTask.NoteAdd -> {
                say(if (actions.addNote(task.text)) phrases.noteSaved() else phrases.taskFailed())
                return Result.ANSWERED
            }
            PhoneTask.NotesRead -> {
                val notes = actions.notes().takeLast(MAX_READ_NOTES)
                if (notes.isEmpty()) {
                    say(phrases.noNotes())
                } else {
                    say(phrases.notesIntro(notes.size))
                    notes.forEachIndexed { i, note -> say("${i + 1}. $note.") }
                }
                return Result.ANSWERED
            }
            PhoneTask.NotesClear -> {
                say(if (actions.clearNotes()) phrases.notesCleared() else phrases.taskFailed())
                return Result.ANSWERED
            }
            PhoneTask.ShowAlarms -> {
                if (!actions.showAlarms()) {
                    say(phrases.taskFailed())
                    return Result.ANSWERED
                }
                say(phrases.openingAlarms())
            }
            PhoneTask.TimeNow, PhoneTask.DateToday, PhoneTask.Capabilities, is PhoneTask.Calculate -> Unit
            // Needs the screen and the app list: done by the engine.
            is PhoneTask.CloseApp -> Unit
        }
        return Result.MOVED
    }

    /** After a failed lookup: the permission screen opened (MOVED), or the name wasn't found (ANSWERED). */
    private var missed = Result.ANSWERED

    /** A spoken number, or a contact found by name; null after explaining why not. */
    private suspend fun contact(actions: PhoneActions, who: String, phrases: Phrases, say: suspend (String) -> Unit): Pair<String, String>? {
        val digits = who.filter { it.isDigit() || it == '+' }
        if (digits.count(Char::isDigit) >= 3 && who.none { it.isLetter() }) return who to digits
        val name = person(who, phrases, say) ?: return null.also { missed = Result.ANSWERED }
        return when (val found = actions.findContact(name)) {
            is ContactResult.Found -> (found.name to found.number).also { lastPerson = name }
            ContactResult.NotFound -> null.also { missed = Result.ANSWERED; say(phrases.contactNotFound(name)) }
            ContactResult.NoPermission -> null.also { missed = Result.MOVED; say(phrases.needContacts()) }
        }
    }

    /** "usko" / "him" becomes the person talked about last; null (after asking who) when there was none. */
    private suspend fun person(who: String, phrases: Phrases, say: suspend (String) -> Unit): String? {
        if (who !in PhoneTask.PRONOUNS) return who
        return lastPerson ?: null.also { say(phrases.whoDoYouMean()) }
    }

    /** 100.0 → "100", 2.5 → "2.5", 3.3333 → "3.33". */
    private fun spokenNumber(value: Double): String =
        if (value % 1.0 == 0.0 && kotlin.math.abs(value) < 1e15) value.toLong().toString()
        else java.math.BigDecimal(value).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    private fun spokenTime(time: LocalTime, locale: Locale): String =
        time.format(DateTimeFormatter.ofPattern(if (time.minute == 0) "h a" else "h:mm a", locale))

    companion object {
        const val EMERGENCY_NUMBER = "112"
        private val yesWords = setOf("haan", "ha", "han", "haa", "yes", "ji", "हाँ", "हां", "जी", "karo", "करो", "please", "call")
        const val MAX_READ_NOTIFICATIONS = 5
        const val MAX_READ_NOTES = 10
        const val MAX_NOTIFICATION_CHARS = 200

        /** Words for buttons the assistant leaves on screen: "bhejo" presses Send, "call karo" presses Call. */
        private val aliases = mapOf(
            "send" to listOf("send", "bhejo", "bhej do", "bhejiye", "भेजो", "भेज दो", "भेजिए", "send karo", "send kar do"),
            "call" to listOf("call", "call karo", "call kar do", "call lagao", "कॉल", "कॉल करो", "कॉल कर दो", "dial", "phone lagao"),
        )
        private val buttonNames = mapOf(
            "send" to listOf("send", "भेजें", "भेजो"),
            "call" to listOf("call", "dial", "voice call", "कॉल करें", "कॉल"),
        )

        /** The chat's Send button, once the chat has opened. */
        fun sendButton(elements: List<ScreenElement>): ScreenElement? =
            elements.firstOrNull { e -> e.isEnabled && !e.kind.isInput && e.label.trim().lowercase() in buttonNames.getValue("send") }

        /** The on-screen Send / Call button the user meant with "bhejo" / "call karo", if one is showing. */
        fun spokenAlias(heard: String, elements: List<ScreenElement>): ScreenElement? {
            val said = heard.trim().lowercase().trimEnd('.', '!', '।')
            val key = aliases.entries.firstOrNull { (_, words) -> said in words }?.key ?: return null
            val names = buttonNames.getValue(key)
            return elements.firstOrNull { e -> e.isEnabled && !e.kind.isInput && e.label.trim().lowercase() in names }
        }
    }
}
