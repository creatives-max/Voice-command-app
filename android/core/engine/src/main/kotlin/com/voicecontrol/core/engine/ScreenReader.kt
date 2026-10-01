package com.voicecontrol.core.engine

import com.voicecontrol.core.model.ElementKind
import com.voicecontrol.core.model.ScreenElement
import com.voicecontrol.core.model.ScreenSnapshot

/** One thing the screen reader reads: a piece of text or an interactive element. */
sealed interface ReaderItem {
    val top: Int
    val left: Int

    data class Text(val text: String, override val top: Int, override val left: Int) : ReaderItem
    data class Element(val element: ScreenElement) : ReaderItem {
        override val top: Int get() = element.bounds.top
        override val left: Int get() = element.bounds.left
    }
}

/**
 * Screen-reader mode for blind and low-vision users: the whole screen (title, text and controls) in
 * reading order, described in the session language. Values of private fields are never read.
 */
object ScreenReader {
    private const val ROW_TOLERANCE_PX = 24

    fun items(snapshot: ScreenSnapshot): List<ReaderItem> {
        val title = snapshot.title?.takeIf { it.isNotBlank() }?.let { ReaderItem.Text(it, Int.MIN_VALUE, 0) }
        val texts = snapshot.texts.map { ReaderItem.Text(it.text, it.bounds.top, it.bounds.left) }
        val elements = snapshot.elements.map { ReaderItem.Element(it) }
        return listOfNotNull(title) + (texts + elements).sortedWith(compareBy({ it.top / ROW_TOLERANCE_PX }, { it.left }))
    }

    fun describe(item: ReaderItem, phrases: Phrases): String = when (item) {
        is ReaderItem.Text -> item.text
        is ReaderItem.Element -> {
            val e = item.element
            when {
                e.kind == ElementKind.BUTTON || e.kind == ElementKind.LINK -> phrases.describeButton(e.label)
                e.kind.isToggle -> phrases.describeToggle(e.label, e.isChecked == true)
                e.isSensitive -> phrases.describePrivate(e.label)
                e.value.isNullOrBlank() -> phrases.describeEmpty(e.label)
                else -> phrases.describeFilled(e.label, e.value!!)
            }
        }
    }
}
