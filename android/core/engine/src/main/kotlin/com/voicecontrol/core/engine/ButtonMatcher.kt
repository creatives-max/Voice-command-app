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

    /** Best button whose label matches [spoken], using exact, containment and token-overlap scoring. */
    fun find(spoken: String, elements: List<ScreenElement>): ScreenElement? {
        val target = normalize(spoken)
        if (target.isEmpty()) return null
        val clickable = elements.filter {
            it.isEnabled && (it.kind == ElementKind.BUTTON || it.kind == ElementKind.LINK || it.kind.isToggle || it.kind == ElementKind.DROPDOWN)
        }
        return clickable
            .map { it to score(target, normalize(it.label)) }
            .filter { it.second >= MIN_SCORE }
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
        if (label.contains(target) || target.contains(label)) return 0.85
        val a = target.split(' ').toSet()
        val b = label.split(' ').toSet()
        val overlap = a.intersect(b).size.toDouble() / a.union(b).size
        val fuzzy = 1.0 - levenshtein(target, label).toDouble() / maxOf(target.length, label.length)
        return maxOf(overlap, fuzzy * 0.9)
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
}
