package com.voicecontrol.core.screen

import com.voicecontrol.core.model.ElementKind

/**
 * Assigns IDs that stay the same for the same element across refreshes and app restarts.
 *
 * Priority:
 * 1. `vid:<resource-id>` when the app sets a view id (suffixed `#n` when ids repeat, e.g. lists).
 * 2. `lbl:<kind>:<hash(normalized label)>` for unlabelled-id elements with a meaningful label.
 * 3. `path:<kind>:<hierarchy path>` as the last resort.
 *
 * Normalized labels drop digits and punctuation, so "Step 2 of 5" vs "Step 3 of 5" keeps the same id.
 */
class StableIdGenerator {

    data class Input(val viewId: String?, val kind: ElementKind, val label: String?, val path: String)

    fun assign(inputs: List<Input>): List<String> {
        val base = inputs.map(::baseId)
        val seen = mutableMapOf<String, Int>()
        return base.map { id ->
            val n = seen.getOrDefault(id, 0)
            seen[id] = n + 1
            if (n == 0) id else "$id#$n"
        }
    }

    private fun baseId(input: Input): String {
        if (!input.viewId.isNullOrBlank()) return "vid:${input.viewId}"
        val normalized = LabelText.normalize(input.label)
        if (normalized.isNotBlank() && normalized !in genericLabels) {
            return "lbl:${input.kind.name.lowercase()}:${LabelText.shortHash(normalized)}"
        }
        return "path:${input.kind.name.lowercase()}:${input.path}"
    }

    private val genericLabels = setOf("text field", "button", "checkbox", "switch", "option", "dropdown", "link")
}
