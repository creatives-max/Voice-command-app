package com.voicecontrol.core.nlp

/** Where a spoken search goes. */
enum class SearchPlace { WEB, YOUTUBE, MAPS }

/**
 * Everyday phone jobs a personal assistant does on request, understood in English, Hinglish and Hindi:
 * "6 baje ka alarm laga do", "set a timer for 5 minutes", "YouTube pe Arijit ke gaane chalao",
 * "Rahul ko call karo", "Mummy ko WhatsApp pe message bhejo ki main aa raha hoon", "time kya hua".
 */
sealed interface PhoneTask {
    data class Alarm(val hour: Int, val minute: Int) : PhoneTask
    data class Timer(val seconds: Int) : PhoneTask
    data class Search(val place: SearchPlace, val query: String) : PhoneTask
    /** [who] is a contact name or a spoken number. */
    data class Call(val who: String) : PhoneTask
    data class Message(val who: String, val text: String?) : PhoneTask
    data object TimeNow : PhoneTask
    data object DateToday : PhoneTask

    companion object {
        fun parse(utterance: String): PhoneTask? {
            val text = TextCleanup.simplify(utterance)
            if (text.isEmpty()) return null
            val words = text.split(' ')
            return time(text) ?: alarm(text, words) ?: timer(text, words) ?: message(text) ?: call(text) ?: search(text, words)
        }

        // --- time and date ---------------------------------------------------------------------

        private val timeAsks = listOf(
            "what time is it", "what's the time", "whats the time", "tell me the time", "current time",
            "time kya hai", "time kya hua", "kitne baje hai", "kitne baje hain", "kitna baja hai", "kitna baj gaya", "abhi kitne baje",
            "टाइम क्या है", "टाइम क्या हुआ", "समय क्या है", "समय क्या हुआ", "कितने बजे हैं", "कितने बजे है", "कितना बजा है", "कितना बज गया",
        )
        private val dateAsks = listOf(
            "what's the date", "whats the date", "what is the date", "today's date", "todays date", "what day is it", "what day is today",
            "aaj kya date hai", "aaj ki date", "aaj kaun sa din", "aaj konsa din", "aaj ki tareekh", "aaj ki tarikh", "aaj kya tarikh",
            "आज की तारीख", "आज क्या तारीख", "आज कौन सा दिन", "आज कौनसा दिन", "आज क्या डेट", "आज की डेट",
        )

        private fun time(text: String): PhoneTask? = when {
            dateAsks.any { it in text } -> DateToday
            timeAsks.any { it in text } -> TimeNow
            else -> null
        }

        // --- alarms ----------------------------------------------------------------------------

        private val alarmWords = setOf("alarm", "अलार्म", "alaram")
        private val wakeUps = listOf("jaga dena", "jaga do", "utha dena", "utha do", "wake me up", "जगा देना", "जगा दो", "उठा देना", "उठा दो")
        private val evening = setOf("pm", "p.m", "shaam", "sham", "शाम", "raat", "rat", "रात", "evening", "night", "dopahar", "dupahar", "दोपहर", "afternoon")
        private val morning = setOf("am", "a.m", "subah", "subha", "सुबह", "morning")
        private val hourWords = mapOf(
            "ten" to 10, "eleven" to 11, "twelve" to 12, "das" to 10, "dus" to 10, "gyarah" to 11, "gyara" to 11, "barah" to 12, "bara" to 12,
            "दस" to 10, "ग्यारह" to 11, "बारह" to 12,
        )

        private fun alarm(text: String, words: List<String>): PhoneTask? {
            if (words.none { it in alarmWords } && wakeUps.none { it in text }) return null
            val (hour, minute) = clock(words) ?: return null
            return Alarm(hour, minute)
        }

        /** "6", "6 30", "6 baje", "saade 6", "sava 7", "paune 8", "7 pm", "shaam 7" → 24-hour time. */
        private fun clock(words: List<String>): Pair<Int, Int>? {
            val at = words.indexOfFirst { hourOf(it) != null }
            if (at < 0) return null
            var hour = hourOf(words[at])!!
            var minute = words.getOrNull(at + 1)?.toIntOrNull()?.takeIf { it in 0..59 } ?: 0
            val before = words.getOrNull(at - 1)
            when (before) {
                "saade", "sade", "साढ़े", "साढे" -> minute = 30
                "sava", "sawa", "सवा" -> minute = 15
                "paune", "पौने" -> { hour -= 1; minute = 45 }
            }
            if (hour !in 0..23) return null
            val pm = words.any { it in evening }
            val am = words.any { it in morning }
            if (pm && hour in 1..11) hour += 12
            if (am && hour == 12) hour = 0
            // "raat 12" is midnight.
            if (pm && hour == 12 && words.any { it == "raat" || it == "रात" || it == "night" }) hour = 0
            return hour to minute
        }

        private fun hourOf(word: String): Int? =
            word.toIntOrNull()?.takeIf { it in 0..23 } ?: hourWords[word] ?: NumberParser.value(word)?.toInt()?.takeIf { it in 1..9 && !word.all(Char::isDigit) && word !in notHours }

        /** Digit words that are also everyday words ("do" = two, but also "do it"). */
        private val notHours = setOf("do", "दो", "no", "oh", "o", "ek", "एक", "sat", "ath", "che", "tin")

        // --- timers ----------------------------------------------------------------------------

        private val timerWords = setOf("timer", "टाइमर")
        private val units = mapOf(
            "second" to 1, "seconds" to 1, "sec" to 1, "secs" to 1, "second." to 1, "सेकंड" to 1, "sekand" to 1,
            "minute" to 60, "minutes" to 60, "min" to 60, "mins" to 60, "minat" to 60, "मिनट" to 60,
            "hour" to 3600, "hours" to 3600, "ghanta" to 3600, "ghante" to 3600, "घंटा" to 3600, "घंटे" to 3600,
        )

        private val halves = setOf("aadha", "adha", "आधा", "half")

        private fun timer(text: String, words: List<String>): PhoneTask? {
            if (words.none { it in timerWords } && listOf("yaad dila", "याद दिला", "remind me in").none { it in text }) return null
            var total = 0
            words.forEachIndexed { i, w ->
                val unit = units[w] ?: return@forEachIndexed
                val prev = words.getOrNull(i - 1)
                total += if (prev in halves) {
                    unit / 2
                } else {
                    val amount = prev?.toIntOrNull() ?: prev?.let { NumberParser.value(it)?.toInt() } ?: 1
                    amount * unit + if (words.getOrNull(i - 2) in setOf("saade", "sade", "साढ़े")) unit / 2 else 0
                }
            }
            return if (total in 1..86_400) Timer(total) else null
        }

        // --- calls and messages ----------------------------------------------------------------

        private val callTails = listOf(
            "ko call karo", "ko call kar do", "ko call lagao", "ko phone karo", "ko phone lagao", "ko phone kar do", "ko call", "ko phone",
            "को कॉल करो", "को कॉल कर दो", "को कॉल लगाओ", "को फोन करो", "को फोन लगाओ", "को फ़ोन करो", "को फ़ोन लगाओ", "को फोन कर दो", "को कॉल", "को फोन",
        )
        private val callHeads = listOf("call to", "call", "phone", "dial", "ring", "कॉल करो", "कॉल", "डायल")

        private fun call(text: String): PhoneTask? {
            for (tail in callTails.sortedByDescending { it.length }) {
                val at = text.indexOf(" $tail")
                if (at > 0 && text.substring(at + tail.length + 1).isBlank()) return who(text.substring(0, at))?.let(::Call)
            }
            for (head in callHeads) {
                if (text.startsWith("$head ")) return who(text.removePrefix("$head "))?.let(::Call)
            }
            return null
        }

        private val messageWords = listOf("message", "msg", "massage", "मैसेज", "मेसेज", "sandesh", "संदेश", "text", "whatsapp")
        private val saying = listOf(" ki ", " कि ", " that ", " saying ", " bolo ", " बोलो ")

        private fun message(text: String): PhoneTask? {
            if (messageWords.none { " $it " in " $text " }) return null
            if (listOf("bhejo", "bhej do", "send", "भेजो", "भेज दो", "karo", "करो", "kar do").none { " $it " in " $text " || text.endsWith(" $it") }) return null
            // The words after "ki"/"that" are the message.
            val cut = saying.mapNotNull { s -> text.indexOf(s).takeIf { it > 0 }?.let { it to s } }.minByOrNull { it.first }
            val head = cut?.let { text.substring(0, it.first) } ?: text
            val body = cut?.let { text.substring(it.first + it.second.length).trim() }?.takeIf { it.isNotEmpty() }
            val name = when {
                " ko " in " $head " -> head.substringBefore(" ko ").removePrefix("ko ")
                " को " in " $head " -> head.substringBefore(" को ")
                head.startsWith("send ") || head.startsWith("message ") -> head.substringAfter(" to ", head.substringAfter("message ", ""))
                else -> ""
            }
            val clean = who(name.split(' ').filter { it !in messageWords && it !in setOf("send", "a", "on", "pe", "par", "पर", "पे") }.joinToString(" ")) ?: return null
            return Message(clean, body)
        }

        private val fillers = setOf("please", "plz", "zara", "ज़रा", "जरा", "mere", "meri", "मेरे", "मेरी", "my", "the", "to", "ko", "को")

        private fun who(raw: String): String? {
            val words = raw.split(' ').filter { it.isNotEmpty() }.toMutableList()
            while (words.isNotEmpty() && words.first() in fillers) words.removeAt(0)
            while (words.isNotEmpty() && words.last() in fillers) words.removeAt(words.lastIndex)
            return words.joinToString(" ").takeIf { it.isNotBlank() && it.length <= 40 }
        }

        // --- searches --------------------------------------------------------------------------

        private val youtube = setOf("youtube", "यूट्यूब", "यूटूब", "yt")
        private val google = setOf("google", "गूगल", "internet", "इंटरनेट", "web")
        private val maps = setOf("maps", "map", "मैप", "मैप्स", "naksha", "नक्शा")
        private val searchVerbs = listOf(
            "search karo", "search kar do", "search karke", "search", "dhundo", "dhoondo", "dhundh do", "khojo", "google karo",
            "सर्च करो", "सर्च कर दो", "सर्च", "ढूंढो", "ढूँढो", "खोजो", "find", "look up", "look for",
        )
        private val playVerbs = listOf("chalao", "chala do", "lagao", "laga do", "bajao", "baja do", "dikhao", "play", "चलाओ", "चला दो", "लगाओ", "बजाओ", "दिखाओ")
        private val routeWords = listOf("ka rasta", "ka raasta", "ka route", "का रास्ता", "navigate to", "directions to", "route to", "kaise jaye", "kaise jaun", "कैसे जाएं", "कैसे जाऊं")
        private val joiners = setOf("pe", "par", "per", "me", "mein", "main", "on", "in", "पे", "पर", "में", "se", "से", "for", "ke", "liye", "के", "लिए", "karo", "करो", "do", "दो", "please", "the")

        private fun search(text: String, words: List<String>): PhoneTask? {
            val place = when {
                words.any { it in youtube } -> SearchPlace.YOUTUBE
                words.any { it in maps } || routeWords.any { it in text } -> SearchPlace.MAPS
                else -> SearchPlace.WEB
            }
            val verbs = searchVerbs + (if (place == SearchPlace.YOUTUBE) playVerbs else emptyList()) + routeWords
            if (verbs.none { " $it " in " $text " }) return null
            var rest = " $text "
            (verbs + youtube + google + maps).sortedByDescending { it.length }.forEach { rest = rest.replace(" $it ", " ") }
            val query = rest.trim().split(' ').filter { it.isNotEmpty() }.toMutableList()
            while (query.isNotEmpty() && query.first() in joiners) query.removeAt(0)
            while (query.isNotEmpty() && query.last() in joiners) query.removeAt(query.lastIndex)
            val q = query.joinToString(" ").takeIf { it.isNotBlank() } ?: return null
            return Search(place, q)
        }
    }
}
