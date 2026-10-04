package com.voicecontrol.core.engine

import com.voicecontrol.core.model.FlowDefinition
import com.voicecontrol.core.model.StepAction

/**
 * A saved flow said with a different item: "Zepto pe doodh order karo" for the flow saved as "Zepto pe
 * maggi order karo". The words that changed replace the value the flow types ("maggi" → "doodh").
 */
object FlowItemSwap {
    private fun words(text: String) = text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    private fun close(a: String, b: String): Boolean {
        if (a == b) return true
        val allowed = if (maxOf(a.length, b.length) <= 5) 1 else 2
        if (kotlin.math.abs(a.length - b.length) > allowed) return false
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length] <= allowed
    }

    /**
     * [flow] as meant by [said] (which matched its shortcut [phrase]): unchanged when the same words were
     * said; with the typed item swapped when only that changed; null when other words changed (the flow
     * would do the wrong thing).
     */
    fun adapt(flow: FlowDefinition, phrase: String, said: String): FlowDefinition? {
        val phraseWords = words(phrase)
        val saidWords = words(said)
        // Misheard spellings ("bijlee" for "bijli", "maggie" for "maggi") are the same word.
        val removed = phraseWords.filter { w -> w !in saidWords && saidWords.none { close(it, w) } }
        val added = saidWords.filter { w -> w !in phraseWords && phraseWords.none { close(it, w) } }
        if (removed.isEmpty()) return flow // the same request, maybe with extra words ("please")
        if (added.isEmpty()) return flow // fewer words, nothing new asked for
        val typed = flow.steps.filter { st ->
            st.action == StepAction.FILL && !st.defaultValue.isNullOrBlank() && words(st.defaultValue!!).all { it in removed }
        }
        // Every changed word must be one the flow types; otherwise this is a different request.
        if (typed.isEmpty() || removed.any { w -> typed.none { w in words(it.defaultValue!!) } }) return null
        val item = added.joinToString(" ")
        return flow.copy(steps = flow.steps.map { st -> if (st in typed) st.copy(defaultValue = item) else st })
    }
}
