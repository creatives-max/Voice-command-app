package com.voicecontrol.feature.assistant.scan

import com.voicecontrol.core.engine.DocField
import com.voicecontrol.core.engine.DocumentFieldMapper
import com.voicecontrol.core.engine.ExtractedDocument
import com.voicecontrol.core.engine.ProposedFill
import com.voicecontrol.core.model.ScreenSnapshot

/** One proposed fill on the review screen; the user can untick it, correct it or reveal a masked value. */
data class ReviewItem(val fill: ProposedFill, val checked: Boolean = true, val value: String = fill.value, val revealed: Boolean = !fill.field.masked) {
    val shown: String get() = if (revealed) value else DocumentFieldMapper.display(fill.field, value)
}

sealed interface ScanStage {
    /** Choose to take a photo or pick one. */
    data object Choose : ScanStage
    data object Reading : ScanStage
    data class Review(val document: ExtractedDocument, val items: List<ReviewItem>, val unused: Map<DocField, String>) : ScanStage
    data class Problem(val message: String) : ScanStage
}

object ScanReview {
    /** What to show after reading a document for [target]. */
    fun stage(target: ScreenSnapshot?, document: ExtractedDocument): ScanStage {
        if (target == null) return ScanStage.Problem("Open the form you want to fill, then tap “Fill from a photo” again.")
        if (document.isEmpty) return ScanStage.Problem("No details found in this photo. Try a sharper photo in good light, with the whole document in view.")
        val fills = DocumentFieldMapper.map(target, document)
        val used = fills.map { it.field }.toSet()
        val unused = document.fields.filterKeys { it !in used }
        if (fills.isEmpty()) {
            return ScanStage.Problem("Found ${document.fields.keys.joinToString { it.label.lowercase() }}, but this screen has no matching fields.")
        }
        return ScanStage.Review(document, fills.map { ReviewItem(it) }, unused)
    }

    /** The reviewed values to fill (ticked, not blank). */
    fun chosen(items: List<ReviewItem>): List<ProposedFill> =
        items.filter { it.checked && it.value.isNotBlank() }.map { it.fill.copy(value = it.value.trim()) }
}
