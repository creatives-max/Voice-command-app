package com.voicecontrol.core.nlp

/**
 * Recognizes control commands in English, Hindi, Hinglish, Marathi, Tamil, Telugu, Bengali and Gujarati.
 *
 * A command must be (almost) the whole utterance, so answers that merely contain a command word
 * ("Next Generation School", "Back office") are not mistaken for commands.
 */
object CommandParser {

    private val politeness = setOf(
        "please", "plz", "now", "karo", "kar", "do", "kariye", "kijiye", "karna", "करो", "कर", "दो", "करें",
        "कीजिए", "करिए", "ji", "जी", "na", "ना", "ab", "अब", "jao", "जाओ", "chalo", "चलो", "go", "the", "to", "par", "pe", "पर", "पे",
        // Marathi, Tamil, Telugu, Bengali, Gujarati
        "करा", "द्या", "kara", "செய்", "செய்யுங்கள்", "பண்ணு", "చేయి", "చేయండి", "cheyi", "cheyandi",
        "করো", "করুন", "দাও", "koro", "કરો", "દો", "આપો",
    )

    private val phrases: List<Pair<VoiceCommand, Set<String>>> = listOf(
        VoiceCommand.Next to setOf("next", "next field", "go next", "move on", "aage", "aage badho", "agla", "agle", "आगे", "अगला", "आगे बढ़ो", "अगले", "पुढे", "पुढील", "पुढचा", "pudhe", "அடுத்து", "அடுத்தது", "adutthu", "aduthu", "తరువాత", "తర్వాత", "ముందుకు", "tharuvatha", "tarvata", "পরের", "পরবর্তী", "এগিয়ে যাও", "porer", "આગળ", "આગળનું", "agal"),
        VoiceCommand.Previous to setOf("previous", "previous field", "last field", "pichla", "pichhla", "पिछला", "पिछले", "मागील", "मागचा", "magcha", "முந்தையது", "முந்தைய", "మునుపటి", "వెనుకటి", "আগের", "ager", "પાછલું", "અગાઉનું", "pachhlu"),
        VoiceCommand.Skip to setOf("skip", "skip it", "skip this", "leave it", "chhodo", "chodo", "chhod", "rehne", "rehne do", "rahne do", "छोड़ो", "छोड़", "रहने", "रहने दो", "वगळा", "सोडा", "राहू द्या", "vagla", "தவிர்", "விடு", "விட்டுவிடு", "వదిలేయి", "దాటవేయి", "বাদ দাও", "এড়িয়ে যাও", "বাদ", "bad dao", "છોડો", "રહેવા દો"),
        VoiceCommand.Submit to setOf("submit", "send", "done", "finish", "save", "confirm", "jama", "submit karo", "bhejo", "bhej", "जमा", "सबमिट", "भेजो", "भेज", "सेव", "जमा करा", "पाठवा", "pathva", "சமர்ப்பி", "அனுப்பு", "சேமி", "సమర్పించు", "పంపు", "సేవ్", "জমা দাও", "জমা", "পাঠাও", "সেভ", "સબમિટ", "જમા કરો", "મોકલો", "mokalo"),
        VoiceCommand.Back to setOf("back", "go back", "wapas", "vapas", "peeche", "piche", "पीछे", "वापस", "बैक", "मागे", "परत", "mage", "பின்னால்", "திரும்ப", "pinnal", "వెనక్కి", "వెనుకకు", "venakki", "পিছনে", "ফিরে যাও", "pichone", "પાછળ", "પાછા", "pachhal"),
        VoiceCommand.ScrollDown to setOf("scroll", "scroll down", "down", "neeche", "niche", "neeche scroll", "नीचे", "स्क्रॉल", "नीचे स्क्रॉल", "खाली", "khali", "கீழே", "keezhe", "కిందకి", "క్రిందకు", "kindaki", "নিচে", "નીચે"),
        VoiceCommand.ScrollUp to setOf("scroll up", "up", "upar", "oopar", "upar scroll", "ऊपर", "ऊपर स्क्रॉल", "वर", "மேலே", "mele", "పైకి", "paiki", "উপরে", "upore", "ઉપર"),
        VoiceCommand.Repeat to setOf("repeat", "again", "say again", "what", "pardon", "phir se", "fir se", "dobara", "dubara", "फिर से", "दोबारा", "क्या", "पुन्हा", "परत सांगा", "punha", "மீண்டும்", "திரும்ப சொல்", "meendum", "మళ్ళీ", "మళ్లీ", "మళ్లీ చెప్పు", "malli", "আবার", "আবার বলো", "abar", "ફરીથી", "ફરી", "farithi"),
        VoiceCommand.Stop to setOf("stop", "cancel", "exit", "quit", "band", "ruko", "ruk", "bas", "बंद", "रुको", "रुक", "बस", "थांबा", "बंद करा", "thamba", "நிறுத்து", "நிறுத்துங்கள்", "niruthu", "ఆపు", "ఆపండి", "aapu", "থামো", "থামুন", "বন্ধ করো", "thamo", "બંધ", "રોકો", "અટકો", "roko"),
        VoiceCommand.Yes to setOf("yes", "ok", "okay", "yeah", "yep", "yup", "sure", "correct", "right", "haan", "han", "ha", "haa", "hanji", "haanji", "theek", "theek hai", "thik hai", "sahi", "हाँ", "हां", "हा", "ठीक", "ठीक है", "सही", "हांजी", "होय", "हो", "ho", "hoy", "ஆம்", "ஆமாம்", "சரி", "aamaa", "aam", "sari", "అవును", "సరే", "avunu", "sare", "হ্যাঁ", "হ্যা", "হাঁ", "ঠিক আছে", "hyan", "thik ache", "હા", "હાં", "બરાબર", "select", "select this", "select it", "this one", "choose this", "activate", "press this", "press it", "open this", "tap this", "chuno", "ise chuno", "ise dabao", "yahi", "चुनो", "इसे चुनो", "इसे दबाओ", "यही", "निवडा", "हे निवडा", "தேர்வு", "இதைத் தேர்வு செய்", "ఎంచుకో", "ఇది ఎంచుకో", "বেছে নাও", "এটা বেছে নাও", "પસંદ કરો", "આ પસંદ કરો"),
        VoiceCommand.No to setOf("no", "nope", "wrong", "nahi", "nahin", "nai", "mat", "galat", "नहीं", "नही", "मत", "गलत", "नाही", "இல்லை", "வேண்டாம்", "illai", "vendam", "కాదు", "వద్దు", "లేదు", "kaadu", "vaddu", "না", "ના", "નહીં"),
        VoiceCommand.Clear to setOf("clear", "erase", "delete", "clear it", "mitao", "hatao", "मिटाओ", "हटाओ", "साफ", "पुसा", "काढा", "அழி", "நீக்கு", "తుడిచివేయి", "తొలగించు", "মুছে ফেলো", "মোছো", "ભૂંસો", "કાઢી નાખો"),
        VoiceCommand.Undo to setOf(
            "undo", "undo that", "undo it", "undo karo", "wapas lo", "vapas lo", "pehle jaisa karo", "पूर्ववत", "पहले जैसा करो", "वापस लो", "अनडू",
            "पूर्ववत करा", "செயல்தவிர்", "முன்பு போல", "రద్దు చేయి", "ముందులా చేయి", "আনডু", "আগের অবস্থায় ফেরাও", "પૂર્વવત્ કરો", "અનડૂ",
        ),
        VoiceCommand.ReadScreen to setOf(
            "read screen", "read the screen", "read all", "read everything", "read", "what's on the screen", "what is on the screen", "what is on screen",
            "screen padho", "sab padho", "padho", "padh ke sunao", "स्क्रीन पढ़ो", "सब पढ़ो", "पढ़ो", "पढ़कर सुनाओ",
            "स्क्रीन वाचा", "सर्व वाचा", "वाचा", "திரையைப் படி", "அனைத்தையும் படி", "படி", "స్క్రీన్ చదువు", "అన్నీ చదువు", "చదువు",
            "স্ক্রিন পড়ো", "সব পড়ো", "পড়ো", "સ્ક્રીન વાંચો", "બધું વાંચો", "વાંચો",
        ),
        VoiceCommand.Help to setOf("help", "madad", "sahayata", "मदद", "सहायता", "मदत", "உதவி", "udhavi", "సహాయం", "sahayam", "সাহায্য", "sahajjo", "મદદ"),
    )

    private val pressVerbsBefore = listOf("press", "click", "click on", "tap", "tap on", "hit", "select", "open", "choose")
    private val pressVerbsAfter = listOf(
        "dabao", "dabaao", "daba do", "dabaiye", "click karo", "click", "pe click", "par click", "दबाओ", "दबाइए", "दबा", "क्लिक", "पर क्लिक", "चुनो", "खोलो", "kholo", "chuno",
        "दाबा", "उघडा", "அழுத்து", "அழுத்துங்கள்", "திற", "నొక్కు", "నొక్కండి", "తెరువు", "চাপো", "চাপুন", "খোলো", "દબાવો", "ખોલો",
    )

    fun parse(utterance: String): VoiceCommand? {
        val simple = TextCleanup.simplify(utterance)
        if (simple.isEmpty()) return null
        val core = simple.split(' ').filter { it !in politeness }.joinToString(" ")
        // Whole-utterance commands first, so "press it" / "इसे चुनो" select the current item instead of a button named "it".
        if (core.isNotEmpty()) phrases.firstOrNull { (_, set) -> core in set }?.first?.let { return it }
        return parsePress(simple)
    }

    private fun parsePress(simple: String): VoiceCommand.Press? {
        for (verb in pressVerbsBefore.sortedByDescending { it.length }) {
            if (simple.startsWith("$verb ")) {
                val target = cleanTarget(simple.removePrefix("$verb "))
                if (target.isNotEmpty()) return VoiceCommand.Press(target)
            }
        }
        for (verb in pressVerbsAfter.sortedByDescending { it.length }) {
            val stripped = simple.split(' ').filterNot { it in setOf("karo", "करो", "कर", "do", "दो") }.joinToString(" ")
            if (stripped.endsWith(" $verb")) {
                val target = cleanTarget(stripped.removeSuffix(" $verb"))
                if (target.isNotEmpty()) return VoiceCommand.Press(target)
            }
        }
        return null
    }

    private fun cleanTarget(raw: String): String =
        raw.removePrefix("the ").removeSuffix(" button").removeSuffix(" बटन").removeSuffix(" wala").removeSuffix(" वाला")
            .removeSuffix(" pe").removeSuffix(" par").removeSuffix(" पर").removeSuffix(" पे").trim()
}
