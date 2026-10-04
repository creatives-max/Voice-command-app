package com.voicecontrol.core.nlp

/**
 * A sentence that stopped mid-way: "Rahul ko …", "doodh aur …", "mujhe mera …". Older speakers often
 * pause like this; the assistant then listens a little longer instead of acting on half a sentence.
 * Hindi puts the verb last, so a sentence ending on a joining word is very likely not finished.
 */
object Unfinished {
    private val joiners = setOf(
        "ki", "ke", "ka", "ko", "aur", "mein", "me", "se", "par", "pe", "to", "toh", "ya", "mera", "meri", "mere", "apna", "apni", "jo",
        "की", "के", "का", "को", "और", "में", "से", "पर", "पे", "तो", "या", "मेरा", "मेरी", "मेरे", "अपना", "अपनी", "जो",
        "and", "or", "the", "a", "an", "to", "for", "of", "with", "my", "from", "on", "at",
    )

    fun looksUnfinished(text: String): Boolean {
        val words = TextCleanup.simplify(text).split(' ').filter { it.isNotEmpty() }
        if (words.size < 2) return false
        return words.last() in joiners
    }
}
