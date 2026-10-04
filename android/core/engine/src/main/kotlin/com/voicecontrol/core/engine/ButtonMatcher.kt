package com.voicecontrol.core.engine

import com.voicecontrol.core.model.ElementKind
import com.voicecontrol.core.model.ScreenElement
import com.voicecontrol.core.nlp.Transliterator
import com.voicecontrol.core.screen.LabelText

/** Finds buttons by spoken name and picks the likely "submit" button of a form. */
object ButtonMatcher {

    private val submitWords = listOf(
        "submit", "continue", "next", "proceed", "sign up", "signup", "register", "create account", "login", "log in",
        "sign in", "save", "pay", "place order", "confirm", "verify", "send", "done", "apply", "book", "get otp", "ok",
        "get started", "start", "go", "search", "शुरू", "खोजें",
        "जमा", "आगे", "सबमिट", "लॉगिन", "सेव", "भेजें", "पुष्टि",
    )

    /**
     * Best button whose label matches [spoken], using exact, containment and token-overlap scoring.
     * [minScore] [STRICT] accepts only exact or containing matches.
     */
    fun find(spoken: String, elements: List<ScreenElement>, minScore: Double = MIN_SCORE): ScreenElement? {
        val target = normalize(spoken)
        if (target.isEmpty()) return null
        val clickable = elements.filter {
            it.isEnabled && (it.kind == ElementKind.BUTTON || it.kind == ElementKind.LINK || it.kind.isToggle || it.kind == ElementKind.DROPDOWN)
        }
        return clickable
            .map { el ->
                val label = normalize(el.label)
                // Sound matching only across scripts (said in Hindi, written in English or the other way):
                // in one script it would turn "back" into a "Bike" button.
                el to maxOf(score(target, label), if (indic(spoken) != indic(el.label)) soundsAlike(target, label) else 0.0)
            }
            .filter { it.second >= minScore }
            .maxByOrNull { it.second }
            ?.first
    }

    /** The button most likely to finish the form, preferring known words and lower-on-screen buttons. */
    fun primarySubmit(elements: List<ScreenElement>): ScreenElement? {
        val buttons = elements.filter { it.kind == ElementKind.BUTTON && it.isEnabled }
        if (buttons.isEmpty()) return null
        val known = buttons.mapNotNull { b ->
            val label = normalize(b.label)
            val index = submitWords.indexOfFirst { w -> label == w || label.startsWith("$w ") || label.endsWith(" $w") }
            if (index >= 0) b to index else null
        }
        if (known.isNotEmpty()) return known.minWith(compareBy<Pair<ScreenElement, Int>> { it.second }.thenByDescending { it.first.bounds.top }).first
        // A form with a single button: that button submits it.
        return buttons.singleOrNull()
    }

    internal fun normalize(s: String): String = LabelText.normalize(Transliterator.toLatin(s))
        .let { if (it.isBlank()) LabelText.normalize(s) else it }

    internal fun score(target: String, label: String): Double {
        if (label.isEmpty()) return 0.0
        if (target == label) return 1.0
        // Whole words only: "ok" is not in "book".
        if (" $label ".contains(" $target ") || " $target ".contains(" $label ")) return 0.85
        val a = target.split(' ').toSet()
        val b = label.split(' ').toSet()
        val overlap = a.intersect(b).size.toDouble() / a.union(b).size
        val fuzzy = 1.0 - levenshtein(target, label).toDouble() / maxOf(target.length, label.length)
        return maxOf(overlap, fuzzy * 0.9)
    }

    /** Written in an Indian script (Devanagari, Bengali, Gujarati, Tamil, Telugu…). */
    private fun indic(text: String) = text.any { it in '\u0900'..'\u0DFF' }

    /**
     * Same sound, different spelling: "रिचार्ज" (richarj) is "Recharge", "प्रोफाइल" (prophail) is "Profile",
     * "कॉल्स" (kols) is "Calls". Words are compared by their consonants after common sound rules; very short
     * words ("pay", "up") never match this way.
     */
    internal fun soundsAlike(target: String, label: String): Double {
        val a = target.split(' ').map(::soundKey).filter { it.isNotEmpty() }
        val b = label.split(' ').map(::soundKey).filter { it.isNotEmpty() }
        if (a.isEmpty() || b.isEmpty() || a.any { it.length < 2 } && a.size == 1) return 0.0
        val sa = a.joinToString(" ")
        val sb = b.joinToString(" ")
        if (sa.length < 2 || sb.length < 2) return 0.0
        return when {
            sa == sb -> 0.9
            a.all { it.length >= 2 } && (" $sb ".contains(" $sa ") || " $sa ".contains(" $sb ")) -> STRICT
            else -> 0.0
        }
    }

    private fun soundKey(word: String): String {
        var w = word.lowercase()
        if (w.length > 3 && w.endsWith("es")) w = w.dropLast(2) else if (w.length > 3 && w.endsWith("s")) w = w.dropLast(1)
        w = w.replace("tion", "shan").replace("ign", "in").replace("dge", "j").replace("ph", "f").replace("ck", "k")
            .replace("sh", "S").replace("ch", "C").replace("th", "t").replace("kh", "k").replace("gh", "g").replace("bh", "b")
            .replace("dh", "d").replace("jh", "j").replace("x", "ks").replace("q", "k").replace("w", "v").replace("z", "j")
        if (w.endsWith("ge")) w = w.dropLast(2) + "j"
        w = w.replace(Regex("c(?=[eiy])"), "s").replace("c", "k")
        val first = w.firstOrNull() ?: return ""
        val rest = w.drop(1).filter { it !in "aeiouyh" }
        val key = (if (first in "aeiouy") "" else first.toString()) + rest
        return key.fold(StringBuilder()) { acc, ch -> if (acc.isEmpty() || acc.last() != ch) acc.append(ch) else acc }.toString()
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }

    private const val MIN_SCORE = 0.6
    const val STRICT = 0.85
}
