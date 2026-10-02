package com.voicecontrol.core.nlp

/**
 * Sentences that state something the user wants done rather than a button to press: "mujhe bijli ka bill
 * bharna hai", "I want to book a train ticket", "recharge kaise kare", "PhonePe kholo aur 100 ka recharge
 * karo". These start the helper that operates the app step by step.
 */
object GoalRequest {
    private val markers = listOf(
        "karna hai", "karni hai", "karne hai", "karna h", "karwana hai", "bharna hai", "bhejna hai", "dekhna hai", "lena hai",
        "banana hai", "chahiye", "chaiye", "kaise kare", "kaise karein", "kaise karte", "kaise karu", "kaise karun", "karke do", "kar ke do",
        "करना है", "करनी है", "भरना है", "भेजना है", "देखना है", "लेना है", "बनाना है", "चाहिए", "कैसे करें", "कैसे करे", "कैसे करूं", "करके दो",
        "i want to", "i need to", "i'd like to", "i would like to", "help me", "how do i", "how to", "how can i", "can you", "please book",
        "please pay", "for me",
    )
    private val joined = listOf(" aur ", " और ", " and then ", " and ", " phir ", " फिर ")

    fun isGoal(utterance: String): Boolean {
        val text = " " + TextCleanup.simplify(utterance) + " "
        if (text.trim().split(' ').size < 3) return false
        if (markers.any { " $it " in text || text.trimEnd().endsWith(" $it") }) return true
        // "PhonePe kholo aur recharge karo": opening an app and doing something there.
        return joined.any { it in text } && AppRequest.parse(text.substringBefore(joined.first { j -> j in text }).trim()) != null
    }
}
