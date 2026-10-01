package com.voicecontrol.core.engine

import com.voicecontrol.core.nlp.Transliterator
import com.voicecontrol.core.screen.LabelText

/**
 * Buttons whose effect can't be taken back (paying, deleting, cancelling an order, signing out…).
 * VoiceControl asks for confirmation before pressing them, even in flows set to press automatically.
 */
object DestructiveActions {
    private val keywords = listOf(
        // English
        "delete", "remove", "erase", "discard", "pay", "pay now", "make payment", "confirm payment", "place order", "buy now", "purchase",
        "transfer", "send money", "withdraw", "cancel order", "cancel booking", "cancel subscription", "cancel ticket", "cancel plan",
        "log out", "logout", "sign out", "unsubscribe", "deactivate", "close account", "delete account", "uninstall", "reset", "clear all",
        "block", "factory reset", "format",
        // Hindi / Hinglish
        "हटाएं", "हटाओ", "मिटाएं", "डिलीट", "रद्द करें", "भुगतान", "पेमेंट", "खरीदें", "लॉग आउट", "hatayein", "radd karein", "bhugtan",
        // Marathi, Tamil, Telugu, Bengali, Gujarati
        "हटवा", "रद्द करा", "पैसे भरा", "நீக்கு", "ரத்து", "செலுத்து", "తొలగించు", "రద్దు", "చెల్లించు", "মুছুন", "বাতিল", "পেমেন্ট", "অর্থপ্রদান",
        "કાઢી નાખો", "રદ કરો", "ચૂકવો", "ચુકવણી",
    )

    private val normalized = keywords.map { LabelText.normalize(it) }.filter { it.isNotEmpty() }

    fun isDestructive(label: String): Boolean {
        val l = LabelText.normalize(label)
        val latin = LabelText.normalize(Transliterator.toLatin(label))
        return normalized.any { k -> containsWords(l, k) || containsWords(latin, k) }
    }

    private fun containsWords(label: String, keyword: String): Boolean =
        label == keyword || label.startsWith("$keyword ") || label.endsWith(" $keyword") || label.contains(" $keyword ")
}
