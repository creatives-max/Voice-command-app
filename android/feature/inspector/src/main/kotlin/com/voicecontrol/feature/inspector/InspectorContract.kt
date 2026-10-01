package com.voicecontrol.feature.inspector

import com.voicecontrol.core.model.ElementKind
import com.voicecontrol.core.model.ScreenElement
import com.voicecontrol.core.model.ScreenSnapshot

data class InspectorState(
    val serviceConnected: Boolean = false,
    val snapshot: ScreenSnapshot? = null,
    val showButtons: Boolean = true,
    val refreshing: Boolean = false,
) {
    val rows: List<ElementRow> = snapshot?.elements
        ?.filter { showButtons || it.kind != ElementKind.BUTTON && it.kind != ElementKind.LINK }
        ?.map(ElementRow::from)
        .orEmpty()
}

/** Display model for one element; sensitive values are rendered as a mask, never the real value. */
data class ElementRow(
    val id: String,
    val title: String,
    val kindLabel: String,
    val valueLabel: String?,
    val isSensitive: Boolean,
    val enabled: Boolean,
) {
    companion object {
        const val MASK = "•••• (hidden)"

        fun from(e: ScreenElement): ElementRow {
            val type = e.fieldType?.name?.lowercase()?.replace('_', ' ')
            val kind = e.kind.name.lowercase().replace('_', ' ')
            return ElementRow(
                id = e.id,
                title = e.label,
                kindLabel = if (type != null) "$kind · $type" else kind,
                valueLabel = when {
                    e.isSensitive -> MASK
                    e.isChecked != null -> if (e.isChecked == true) "checked" else "unchecked"
                    else -> e.value
                },
                isSensitive = e.isSensitive,
                enabled = e.isEnabled,
            )
        }
    }
}

sealed interface InspectorIntent {
    data object Refresh : InspectorIntent
    data class ToggleButtons(val show: Boolean) : InspectorIntent
}

sealed interface InspectorEffect {
    data class Message(val text: String) : InspectorEffect
}
