package com.voicecontrol.core.engine

import com.voicecontrol.core.model.FlowDefinition
import com.voicecontrol.core.model.ScreenElement
import com.voicecontrol.core.screen.LabelText

/**
 * Pop-ups that get in a saved flow's way ("Rate us", "Turn on notifications", an offer): the button that
 * just closes one ("Not now", "Skip", "Maybe later"). Never "Cancel" or "OK", which can undo or agree to
 * something, and never a button the flow itself presses.
 */
object PopupCloser {
    private val CLOSE_WORDS = setOf(
        "not now", "skip", "later", "maybe later", "remind me later", "no thanks", "no thank you", "close", "dismiss",
        "x", "abhi nahi", "baad mein", "baad me", "अभी नहीं", "बाद में", "छोड़ें", "रहने दें", "बंद करें",
    )

    fun find(elements: List<ScreenElement>, flow: FlowDefinition?): ScreenElement? {
        val pressedByFlow = flow?.steps.orEmpty().map { LabelText.normalize(it.label) }.toSet()
        return elements.firstOrNull { e ->
            val label = LabelText.normalize(e.label)
            !e.kind.isInput && !e.kind.isToggle && e.isEnabled && label in CLOSE_WORDS && label !in pressedByFlow
        }
    }
}
