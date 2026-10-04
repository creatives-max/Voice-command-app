package com.voicecontrol.application.ai

import com.voicecontrol.domain.ai.ElementKind
import com.voicecontrol.domain.ai.ScreenContext

/**
 * A smaller screen for the helper model: short ids in place of the app's long view ids, buttons with no
 * name dropped (the model can't use them), long labels cut, and visible texts that only repeat a label
 * left out. [realId] turns the model's short id back into the phone's id.
 */
class AgentScreen private constructor(val screen: ScreenContext, val texts: List<String>, private val ids: Map<String, String>) {
    fun realId(id: String): String = ids[id] ?: id

    companion object {
        private val unnamed = setOf("button", "link", "")

        fun compact(screen: ScreenContext, texts: List<String>): AgentScreen {
            val kept = screen.elements.filter { e ->
                e.kind == ElementKind.TEXT_FIELD || e.label.trim().lowercase() !in unnamed || !e.hint.isNullOrBlank()
            }
            val ids = LinkedHashMap<String, String>()
            val elements = kept.mapIndexed { i, e ->
                val short = "e${i + 1}"
                ids[short] = e.id
                e.copy(
                    id = short,
                    label = e.label.take(MAX_LABEL),
                    hint = e.hint?.take(MAX_LABEL)?.takeIf { it.isNotBlank() && !it.equals(e.label, ignoreCase = true) },
                    value = e.value?.take(MAX_VALUE),
                )
            }
            val labels = kept.map { it.label.trim().lowercase() }.toSet()
            val shownTexts = texts.map { it.trim() }.filter { it.isNotEmpty() && it.lowercase() !in labels }.distinct()
            return AgentScreen(screen.copy(elements = elements), shownTexts, ids)
        }

        private const val MAX_LABEL = 80
        private const val MAX_VALUE = 120
    }
}
