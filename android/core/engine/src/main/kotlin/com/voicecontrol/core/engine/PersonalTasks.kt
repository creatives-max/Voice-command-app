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
            PhoneTask.TimeNow, PhoneTask.DateToday -> Unit
        }
        return Result.MOVED
    }

    /** After a failed lookup: the permission screen opened (MOVED), or the name wasn't found (ANSWERED). */
    private var missed = Result.ANSWERED

    /** A spoken number, or a contact found by name; null after explaining why not. */
    private suspend fun contact(actions: PhoneActions, who: String, phrases: Phrases, say: suspend (String) -> Unit): Pair<String, String>? {
        val digits = who.filter { it.isDigit() || it == '+' }
        if (digits.count(Char::isDigit) >= 3 && who.none { it.isLetter() }) return who to digits
        return when (val found = actions.findContact(who)) {
            is ContactResult.Found -> found.name to found.number
            ContactResult.NotFound -> null.also { missed = Result.ANSWERED; say(phrases.contactNotFound(who)) }
            ContactResult.NoPermission -> null.also { missed = Result.MOVED; say(phrases.needContacts()) }
        }
    }

    private fun spokenTime(time: LocalTime, locale: Locale): String =
        time.format(DateTimeFormatter.ofPattern(if (time.minute == 0) "h a" else "h:mm a", locale))

    companion object {
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
