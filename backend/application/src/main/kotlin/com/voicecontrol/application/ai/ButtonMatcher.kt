package com.voicecontrol.application.ai

import com.voicecontrol.application.nlp.Transliterator
import com.voicecontrol.domain.ai.ElementKind
import com.voicecontrol.domain.ai.ScreenElement

/** Finds the clickable element whose label best matches a spoken name. */
object ButtonMatcher {
    private val nonWord = Regex("[^\\p{L}\\p{M}\\p{N} ]")
    private val spaces = Regex("\\s+")

    fun find(spoken: String, elements: List<ScreenElement>): ScreenElement? {
        val target = normalize(spoken)
        if (target.isEmpty()) return null
        return elements
            .filter { it.isEnabled && it.kind != ElementKind.TEXT_FIELD }
            .map { it to score(target, normalize(it.label)) }
            .filter { it.second >= 0.6 }
            .maxByOrNull { it.second }
            ?.first
    }

    fun normalize(s: String): String =
        Transliterator.devanagariToLatin(s).lowercase().replace(nonWord, " ").replace(spaces, " ").trim()

    private fun score(target: String, label: String): Double {
        if (label.isEmpty()) return 0.0
        if (target == label) return 1.0
        if (label.contains(target) || target.contains(label)) return 0.85
        val a = target.split(' ').toSet()
        val b = label.split(' ').toSet()
        return a.intersect(b).size.toDouble() / a.union(b).size
    }
}
