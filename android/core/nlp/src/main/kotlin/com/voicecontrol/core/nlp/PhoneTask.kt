package com.voicecontrol.core.nlp

/** Where a spoken search goes. */
enum class SearchPlace { WEB, YOUTUBE, MAPS }

enum class VolumeChange { UP, DOWN, MUTE, MAX }

/** System settings pages people ask for by name. */
enum class SettingsPage { WIFI, BLUETOOTH, INTERNET, DISPLAY, SOUND, BATTERY, LOCATION, AIRPLANE, DO_NOT_DISTURB, MAIN }

/** Things the phone itself does (accessibility global actions). */
enum class SystemAction { HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS, LOCK, SCREENSHOT, POWER_MENU }

enum class MediaKey { PLAY, PAUSE, NEXT, PREVIOUS }

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
    /** "9 baje dawai ki yaad dilana": an alarm at that time labelled with what to remember. */
    data class Reminder(val hour: Int, val minute: Int, val text: String?) : PhoneTask
    data class Torch(val on: Boolean) : PhoneTask
    data class Volume(val change: VolumeChange) : PhoneTask
    data object Battery : PhoneTask
    data class OpenSettings(val page: SettingsPage) : PhoneTask
    /** "kya naya message aaya": read the latest notifications aloud. */
    data object ReadNotifications : PhoneTask
    /** "tum kya kya kar sakte ho": a short tour of what the assistant does. */
    data object Capabilities : PhoneTask
    data class System(val action: SystemAction) : PhoneTask
    data class Camera(val video: Boolean, val selfie: Boolean) : PhoneTask
    /** Screen brightness; [VolumeChange.MUTE] means lowest. */
    data class Brightness(val change: VolumeChange) : PhoneTask
    data class Media(val key: MediaKey) : PhoneTask
    /** "25 guna 4 kitna hota hai": worked out on the phone. */
    data class Calculate(val result: Double) : PhoneTask
    /** "bachao", "emergency", "SOS": call the emergency contact. */
    data object Emergency : PhoneTask
    /** "mera emergency contact Rahul hai". */
    data class SetEmergencyContact(val who: String) : PhoneTask
    /** "Rahul ka number kya hai". */
    data class ContactNumber(val who: String) : PhoneTask
    /** "note karo ki doodh lana hai": a note kept on the phone. */
    data class NoteAdd(val text: String) : PhoneTask
    data object NotesRead : PhoneTask
    data object NotesClear : PhoneTask
    /** "mere alarm dikhao": the clock app's alarm list. */
    data object ShowAlarms : PhoneTask

    companion object {
        fun parse(utterance: String): PhoneTask? {
            val text = TextCleanup.simplify(utterance)
            if (text.isEmpty()) return null
            val words = text.split(' ')
            return emergency(text, words) ?: notes(text) ?: contactNumber(text) ?: showAlarms(text, words) ?: quickSearch(text, words) ?: capabilities(text) ?: time(text) ?: calculate(words) ?: system(text, words) ?: device(text, words) ?: notifications(text) ?:
                camera(text, words) ?: media(text, words) ?: reminder(text, words) ?: alarm(text, words) ?:
                timer(text, words) ?: message(text) ?: call(text) ?: search(text, words)
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

        // --- emergency and contacts --------------------------------------------------------------

        private val sosWords = setOf("emergency", "sos", "bachao", "bachaao", "बचाओ", "इमरजेंसी", "एमरजेंसी")
        private val contactFillers = setOf("mera", "meri", "my", "mere", "मेरा", "मेरी", "set", "karo", "करो", "hai", "है", "as", "is", "ko", "को", "banao", "बनाओ", "the", "to")
        private val emergencyContactAsks = listOf("emergency contact", "इमरजेंसी कॉन्टैक्ट", "emergency number")

        private fun emergency(text: String, words: List<String>): PhoneTask? {
            if (emergencyContactAsks.any { it in text }) {
                // "mera emergency contact Rahul hai" / "set Rahul as my emergency contact"
                val name = text.replace(Regex("(emergency contact|emergency number|इमरजेंसी कॉन्टैक्ट)"), " ")
                    .split(' ').filter { it.isNotBlank() && it !in contactFillers }.joinToString(" ")
                return if (name.isNotBlank() && name.length <= 40) SetEmergencyContact(name) else null
            }
            // Only clear calls for help; "help" alone means "what can I do here".
            return if (words.any { it in sosWords } || "help me please" in text || "madad karo" in text || "मदद करो" in text) Emergency else null
        }

        private val numberAsks = listOf(" ka number kya hai", " ka number batao", " ka number bolo", " ka phone number", " का नंबर क्या है", " का नंबर बताओ", "what is the number of ", "what's the number of ")

        private fun contactNumber(text: String): PhoneTask? {
            val padded = " $text"
            for (ask in numberAsks) {
                val at = padded.indexOf(ask)
                if (at < 0) continue
                val name = if (ask.startsWith("what")) padded.substring(at + ask.length) else padded.substring(0, at)
                val clean = name.trim().split(' ').filter { it.isNotEmpty() && it !in setOf("mera", "meri", "my", "mere", "मेरे", "मेरा") }.joinToString(" ")
                return clean.takeIf { it.isNotBlank() && it.length <= 40 }?.let(::ContactNumber)
            }
            return null
        }

        // --- notes ------------------------------------------------------------------------------

        private val noteStarts = listOf(
            "note karo ki", "note kar lo ki", "note karo", "note kar lo", "likh lo ki", "likh lo", "yaad rakhna ki", "make a note that", "make a note",
            "take a note", "note down", "note that", "note", "नोट करो कि", "नोट करो", "नोट कर लो", "लिख लो कि", "लिख लो", "याद रखना कि",
        )
        private val notesRead = listOf("notes padho", "notes sunao", "note padho", "read my notes", "read notes", "kya likha tha", "my notes", "mere notes", "नोट्स पढ़ो", "नोट्स सुनाओ", "मेरे नोट्स")
        private val notesClear = listOf("notes mita do", "notes delete", "notes clear", "delete my notes", "clear my notes", "notes hatao", "नोट्स मिटा दो", "नोट्स हटाओ")

        private fun notes(text: String): PhoneTask? {
            if (notesClear.any { it in text }) return NotesClear
            if (notesRead.any { it in text } && noteStarts.none { text.startsWith("$it ") && it.length > 4 }) return NotesRead
            for (start in noteStarts.sortedByDescending { it.length }) {
                if (text.startsWith("$start ")) {
                    val body = text.removePrefix("$start ").removePrefix(": ").trim()
                    return body.takeIf { it.length >= 2 }?.let { NoteAdd(it.take(300)) }
                }
            }
            return null
        }

        private fun showAlarms(text: String, words: List<String>): PhoneTask? =
            if (words.any { it in alarmWords } && words.any { it in setOf("dikhao", "show", "list", "mere", "my", "hatao", "cancel", "delete", "दिखाओ", "हटाओ", "मेरे") } && words.none { it.toIntOrNull() != null }) ShowAlarms else null

        // --- weather, news, scores ---------------------------------------------------------------

        private fun quickSearch(text: String, words: List<String>): PhoneTask? = when {
            // "google pe weather in pune search karo" says exactly what to search.
            searchVerbs.any { " $it " in " $text " } -> null
            words.any { it in setOf("mausam", "weather", "मौसम", "baarish", "barish", "बारिश") } -> Search(SearchPlace.WEB, "weather today")
            words.any { it in setOf("khabar", "khabre", "news", "samachar", "खबर", "खबरें", "समाचार") } && words.none { it in youtube } -> Search(SearchPlace.WEB, "latest news")
            words.any { it in setOf("score", "स्कोर") } && words.any { it in setOf("cricket", "match", "क्रिकेट", "मैच") } -> Search(SearchPlace.WEB, "cricket score")
            else -> null
        }

        // --- what can you do ------------------------------------------------------------------------

        private val capabilityAsks = listOf(
            "what can you do", "what all can you do", "what do you do", "help me with what", "your features",
            "tum kya kya kar sakte ho", "tum kya kar sakte ho", "aap kya kya kar sakte ho", "aap kya kar sakte ho", "kya kya kar sakte ho",
            "तुम क्या क्या कर सकते हो", "तुम क्या कर सकते हो", "आप क्या क्या कर सकते हो", "आप क्या कर सकते हो", "क्या क्या कर सकते हो",
        )

        private fun capabilities(text: String): PhoneTask? = if (capabilityAsks.any { it in text }) Capabilities else null

        // --- phone controls ----------------------------------------------------------------------

        private val torchWords = setOf("torch", "flashlight", "flash", "टॉर्च", "बत्ती", "light")
        private val onWords = setOf("on", "jalao", "jala", "chalu", "chala", "kholo", "जलाओ", "जला", "चालू", "ऑन", "start")
        private val offWords = setOf("off", "band", "bujhao", "bujha", "बंद", "बुझाओ", "ऑफ", "stop")
        private val volumeWords = setOf("volume", "awaaz", "awaz", "aawaz", "sound", "आवाज़", "आवाज", "वॉल्यूम")
        private val upWords = setOf("up", "badhao", "badha", "tez", "zyada", "jyada", "increase", "louder", "बढ़ाओ", "बढ़ा", "तेज़", "तेज", "ज़्यादा", "ज्यादा")
        private val downWords = setOf("down", "kam", "ghatao", "dheere", "dheemi", "decrease", "lower", "softer", "कम", "घटाओ", "धीमी", "धीरे")
        private val muteWords = setOf("mute", "silent", "chup", "band", "बंद", "म्यूट", "साइलेंट")
        private val maxWords = setOf("full", "max", "maximum", "poori", "puri", "पूरी", "फुल")
        private val silentWords = setOf("silent", "mute", "chup", "साइलेंट", "म्यूट", "vibrate", "वाइब्रेट")
        private val brightnessWords = setOf("brightness", "roshni", "chamak", "ब्राइटनेस", "रोशनी", "चमक")
        private val batteryAsks = listOf("battery kitni", "battery kitna", "battery level", "how much battery", "battery percentage", "charge kitna", "बैटरी कितनी", "बैटरी कितना", "चार्ज कितना")
        private val settingsWords = setOf("settings", "setting", "सेटिंग", "सेटिंग्स")
        private val pages = mapOf(
            SettingsPage.WIFI to setOf("wifi", "wi-fi", "वाईफाई", "वाई-फाई"),
            SettingsPage.BLUETOOTH to setOf("bluetooth", "ब्लूटूथ"),
            SettingsPage.INTERNET to setOf("internet", "data", "mobile data", "इंटरनेट", "डेटा"),
            SettingsPage.DISPLAY to setOf("display", "brightness", "screen", "डिस्प्ले", "ब्राइटनेस"),
            SettingsPage.SOUND to setOf("sound", "ringtone", "साउंड", "रिंगटोन"),
            SettingsPage.BATTERY to setOf("battery", "बैटरी"),
            SettingsPage.LOCATION to setOf("location", "gps", "लोकेशन"),
            SettingsPage.AIRPLANE to setOf("airplane mode", "aeroplane mode", "flight mode", "airplane", "flight", "फ्लाइट मोड", "एयरप्लेन"),
            SettingsPage.DO_NOT_DISTURB to setOf("do not disturb", "dnd", "डू नॉट डिस्टर्ब"),
        )

        private fun device(text: String, words: List<String>): PhoneTask? {
            if (batteryAsks.any { it in text }) return Battery
            if (words.any { it in torchWords } && (words.any { it in onWords } || words.any { it in offWords })) {
                // "torch band karo" turns it off; "torch jalao" / "torch on" turns it on.
                return Torch(on = words.none { it in offWords })
            }
            if (words.any { it in volumeWords }) {
                when {
                    words.any { it in maxWords } -> return Volume(VolumeChange.MAX)
                    words.any { it in upWords } -> return Volume(VolumeChange.UP)
                    words.any { it in downWords } -> return Volume(VolumeChange.DOWN)
                    words.any { it in muteWords } -> return Volume(VolumeChange.MUTE)
                }
            }
            if (words.any { it in brightnessWords }) {
                when {
                    words.any { it in maxWords } -> return Brightness(VolumeChange.MAX)
                    words.any { it in upWords } -> return Brightness(VolumeChange.UP)
                    words.any { it in downWords } -> return Brightness(VolumeChange.DOWN)
                }
            }
            if (words.any { it in silentWords } && words.any { it == "phone" || it == "फोन" || it == "mobile" }) return Volume(VolumeChange.MUTE)
            val opens = words.any { it in onWords || it in settingsWords || it == "open" || it == "खोलो" }
            if (opens) {
                val page = pages.entries.firstOrNull { (_, names) -> names.any { n -> " $n " in " $text " } }?.key
                if (page != null && page != SettingsPage.BATTERY && page != SettingsPage.SOUND && page != SettingsPage.DISPLAY) return OpenSettings(page)
                if (page != null && words.any { it in settingsWords }) return OpenSettings(page)
                if (words.any { it in settingsWords } && words.size <= 3) return OpenSettings(SettingsPage.MAIN)
            }
            return null
        }

        // --- the phone itself --------------------------------------------------------------------

        private val goWords = setOf("jao", "chalo", "le", "go", "open", "kholo", "dikhao", "show", "pe", "par", "screen", "जाओ", "चलो", "खोलो", "दिखाओ", "पर", "पे")
        private val phoneWords = setOf("phone", "mobile", "फोन", "फ़ोन", "मोबाइल")

        private fun system(text: String, words: List<String>): PhoneTask? = when {
            words.any { it == "screenshot" || it == "स्क्रीनशॉट" } -> System(SystemAction.SCREENSHOT)
            "quick settings" in text || "quick setting" in text -> System(SystemAction.QUICK_SETTINGS)
            ("recent" in text || "recents" in text || "khule apps" in text || "खुले ऐप" in text) && words.size <= 5 -> System(SystemAction.RECENTS)
            (words.any { it == "notification" || it == "notifications" || it == "नोटिफिकेशन" }) &&
                words.any { it in setOf("kholo", "dikhao", "open", "show", "panel", "खोलो", "दिखाओ") } -> System(SystemAction.NOTIFICATIONS)
            words.any { it == "lock" || it == "लॉक" } && (words.any { it in phoneWords } || "screen" in text || words.size <= 3) -> System(SystemAction.LOCK)
            "power menu" in text || "power button" in text ||
                (words.any { it in phoneWords } && words.any { it in setOf("off", "band", "restart", "reboot", "बंद", "ऑफ", "रीस्टार्ट") } && words.none { it in silentWords }) ->
                System(SystemAction.POWER_MENU)
            words.any { it == "home" || it == "होम" } && words.any { it in goWords } && words.size <= 5 -> System(SystemAction.HOME)
            else -> null
        }

        private val cameraWords = setOf("camera", "कैमरा", "selfie", "सेल्फी")
        private val photoAsks = listOf("photo khincho", "photo lo", "photo le lo", "photo kheencho", "take a photo", "take a picture", "फोटो खींचो", "फोटो लो")
        private val videoAsks = listOf("video banao", "video record", "record video", "video bana", "वीडियो बनाओ", "वीडियो रिकॉर्ड")

        private fun camera(text: String, words: List<String>): PhoneTask? {
            val video = videoAsks.any { it in text }
            if (!video && photoAsks.none { it in text } && words.none { it in cameraWords }) return null
            if (words.any { it in cameraWords } && words.none { it in onWords || it in goWords || it == "selfie" || it == "सेल्फी" }) return null
            return Camera(video = video, selfie = words.any { it == "selfie" || it == "सेल्फी" || it == "front" })
        }

        private val musicWords = setOf("gaana", "gana", "gaane", "gane", "song", "songs", "music", "गाना", "गाने", "म्यूजिक")
        private val pauseWords = setOf("roko", "rok", "ruko", "pause", "stop", "band", "रोको", "रुको", "बंद")
        private val nextWords = setOf("agla", "next", "agle", "अगला")
        private val previousWords = setOf("pichhla", "pichla", "previous", "pehle", "पिछला")
        private val musicFillers = setOf("karo", "do", "kar", "please", "zara", "wala", "करो", "दो", "the", "a")

        private fun media(text: String, words: List<String>): PhoneTask? {
            if (words.none { it in musicWords }) return null
            if (words.any { it in youtube }) return null
            val rest = words.filter { it !in musicWords && it !in musicFillers }
            return when {
                rest.any { it in nextWords } && rest.size <= 2 -> Media(MediaKey.NEXT)
                rest.any { it in previousWords } && rest.size <= 2 -> Media(MediaKey.PREVIOUS)
                rest.any { it in pauseWords } && rest.size <= 2 -> Media(MediaKey.PAUSE)
                rest.all { it in playVerbs || it == "play" } -> Media(MediaKey.PLAY)
                // "Arijit ke gaane chalao": find them on YouTube.
                rest.any { it in playVerbs } -> {
                    val query = words.filter { it !in playVerbs && it !in musicFillers }.joinToString(" ")
                    Search(SearchPlace.YOUTUBE, query)
                }
                else -> null
            }
        }

        // --- arithmetic --------------------------------------------------------------------------

        private val plus = setOf("plus", "+", "jodo", "jod", "जोड़ो", "जोड़", "प्लस", "add")
        private val minus = setOf("minus", "-", "ghatao", "ghata", "घटाओ", "माइनस", "subtract")
        private val times = setOf("guna", "times", "into", "x", "*", "multiply", "multiplied", "गुणा", "गुना")
        private val divide = setOf("divided", "divide", "bhag", "batta", "/", "÷", "भाग", "बटा")
        private val percent = setOf("percent", "pratishat", "%", "प्रतिशत", "परसेंट")

        /** Two numbers and an operation: "25 guna 4", "100 ka 18 percent", "50 divided by 5", "7 plus 8". */
        private fun calculate(words: List<String>): PhoneTask? {
            val numbers = words.mapIndexedNotNull { i, w -> w.replace(",", "").toDoubleOrNull()?.let { i to it } }
            if (numbers.size != 2) return null
            val (ai, a) = numbers[0]
            val (bi, b) = numbers[1]
            val between = words.subList(ai + 1, bi)
            val after = words.drop(bi + 1)
            val result = when {
                (between + after).any { it in percent } -> a * b / 100
                between.any { it in times } -> a * b
                between.any { it in divide } -> if (b == 0.0) return null else a / b
                between.any { it in plus } || after.any { it in plus } -> a + b
                between.any { it in minus } || after.any { it in minus } -> a - b
                // "100 mein se 30 ghatao" puts the verb at the end.
                else -> return null
            }
            return Calculate(result)
        }

        // --- notifications -----------------------------------------------------------------------

        private val notificationAsks = listOf(
            "read my messages", "read my notifications", "any new message", "new messages", "read notifications", "what are my notifications",
            "kya naya message", "naya message aaya", "naye message", "mere message padho", "messages padho", "message padh", "notification padho",
            "notifications padho", "kisne message kiya", "kiska message aaya", "koi message aaya",
            "नया मैसेज", "नए मैसेज", "मैसेज पढ़ो", "मेसेज पढ़ो", "नोटिफिकेशन पढ़ो", "किसका मैसेज", "कोई मैसेज आया",
        )

        private fun notifications(text: String): PhoneTask? = if (notificationAsks.any { it in text }) ReadNotifications else null

        // --- reminders ---------------------------------------------------------------------------

        private val remindWords = listOf("yaad dilana", "yaad dila dena", "yaad dila do", "yaad karana", "remind me", "reminder", "याद दिलाना", "याद दिला देना", "याद दिला दो", "रिमाइंडर")
        private val reminderFillers = setOf(
            "yaad", "dilana", "dila", "dena", "do", "karana", "remind", "me", "reminder", "set", "a", "at", "to", "for", "laga", "lagao",
            "baje", "बजे", "ko", "को", "ki", "की", "ka", "का", "ke", "के", "subah", "shaam", "sham", "raat", "dopahar", "सुबह", "शाम", "रात", "दोपहर",
            "am", "pm", "morning", "evening", "night", "याद", "दिलाना", "दिला", "देना", "दो", "रिमाइंडर", "saade", "sade", "sava", "paune", "साढ़े", "सवा", "पौने",
        )

        private fun reminder(text: String, words: List<String>): PhoneTask? {
            if (remindWords.none { it in text }) return null
            // "10 minute baad yaad dilana" is a timer.
            if (words.any { it in units }) return null
            val (hour, minute) = clock(words) ?: return null
            val about = words.filter { w -> w !in reminderFillers && w.toIntOrNull() == null && hourWords[w] == null }
                .joinToString(" ").takeIf { it.isNotBlank() }
            return Reminder(hour, minute, about)
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
