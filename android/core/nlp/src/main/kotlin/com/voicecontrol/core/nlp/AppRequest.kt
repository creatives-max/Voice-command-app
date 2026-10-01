package com.voicecontrol.core.nlp

/**
 * "open WhatsApp", "launch YouTube", "WhatsApp kholo", "यूट्यूब खोलो", "फोनपे चलाओ", "Gmail ખોલો"…
 * Returns the app name the user asked to open, or null. Whether it is an app or a button on the screen is
 * decided by the caller (a visible button with that name wins).
 */
object AppRequest {
    private val before = listOf("open app", "open the", "open", "launch", "start", "go to", "run", "kholo", "khol do", "खोलो", "चलाओ")
    private val after = listOf(
        "kholo", "khol do", "kholiye", "khol", "open karo", "open kar do", "open kijiye", "open", "chalao", "chalu karo", "start karo", "launch karo",
        "खोलो", "खोल दो", "खोलिए", "खोल", "ओपन करो", "ओपन", "चलाओ", "चालू करो",
        "उघडा", "उघड", "திற", "திறக்கவும்", "తెరువు", "తెరవండి", "ওপেন করো", "খোলো", "খুলুন", "ખોલો", "ખોલી દો",
    )
    private val appWords = setOf("app", "ऐप", "एप", "application", "అప్", "యాప్", "অ্যাপ", "એપ", "ஆப்", "अॅप", "ko", "को", "ஐ")
    private val politeness = setOf("please", "plz", "zara", "ज़रा", "जरा", "na", "ना", "do", "दो", "karo", "करो", "kariye", "कीजिए", "ji", "जी")

    fun parse(utterance: String): String? {
        val words = TextCleanup.simplify(utterance).split(' ').filter { it.isNotEmpty() && it !in politeness }
        if (words.isEmpty()) return null
        val text = words.joinToString(" ")
        for (verb in before.sortedByDescending { it.length }) {
            if (text.startsWith("$verb ")) return clean(text.removePrefix("$verb "))
        }
        for (verb in after.sortedByDescending { it.length }) {
            if (text.endsWith(" $verb")) return clean(text.removeSuffix(" $verb"))
        }
        return null
    }

    private fun clean(name: String): String? {
        val words = name.split(' ').filter { it.isNotEmpty() }.toMutableList()
        while (words.isNotEmpty() && words.last() in appWords) words.removeAt(words.lastIndex)
        while (words.isNotEmpty() && (words.first() in appWords || words.first() == "the")) words.removeAt(0)
        return words.joinToString(" ").takeIf { it.isNotBlank() && it.length <= MAX_NAME }
    }

    private const val MAX_NAME = 40
}
