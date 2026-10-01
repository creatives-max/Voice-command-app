package com.voicecontrol.core.engine

import com.voicecontrol.core.model.Bounds
import com.voicecontrol.core.model.ElementKind
import com.voicecontrol.core.model.ScreenElement
import com.voicecontrol.core.model.ScreenSnapshot
import kotlin.math.max
import kotlin.math.min

/**
 * Hybrid vision: when a screen is only partly readable through accessibility (unlabelled inputs,
 * custom-drawn or web content), the screenshot model's detections are merged in:
 * - a detection overlapping a readable element only repairs that element's missing label/type,
 * - a detection with no readable counterpart is added as a `vision:` element (operated by taps).
 */
object HybridVision {
    private val genericLabels = setOf("text field", "button", "checkbox", "switch", "option", "dropdown", "link", "edit text", "")
    private const val MIN_IOU = 0.3

    fun isGeneric(label: String) = label.trim().lowercase() in genericLabels

    /** Whether the screenshot model could help: unlabelled controls, or (almost) nothing to fill. */
    fun needsHelp(snapshot: ScreenSnapshot): Boolean {
        val controls = snapshot.elements.filter { it.kind.isInput || it.kind.isToggle }
        if (controls.any { isGeneric(it.label) }) return true
        val buttons = snapshot.elements.count { it.kind == ElementKind.BUTTON || it.kind == ElementKind.LINK }
        return controls.isEmpty() && buttons <= 1 && snapshot.texts.size >= 2
    }

    data class Result(val elements: List<ScreenElement>, val added: List<ScreenElement>)

    fun merge(readable: List<ScreenElement>, detected: List<ScreenElement>): Result {
        val repaired = readable.toMutableList()
        val added = mutableListOf<ScreenElement>()
        detected.filter { !it.bounds.isEmpty }.forEach { v ->
            val match = repaired.withIndex().maxByOrNull { iou(it.value.bounds, v.bounds) }
            if (match != null && iou(match.value.bounds, v.bounds) >= MIN_IOU) {
                val r = match.value
                if (isGeneric(r.label) && !isGeneric(v.label)) {
                    repaired[match.index] = r.copy(label = v.label, fieldType = r.fieldType ?: v.fieldType)
                }
            } else if (readable.none { contains(it.bounds, v.bounds.centerX, v.bounds.centerY) }) {
                added += v
            }
        }
        val all = (repaired + added).sortedWith(compareBy({ it.bounds.top / 24 }, { it.bounds.left }))
        return Result(all, added)
    }

    fun iou(a: Bounds, b: Bounds): Double {
        val w = min(a.right, b.right) - max(a.left, b.left)
        val h = min(a.bottom, b.bottom) - max(a.top, b.top)
        if (w <= 0 || h <= 0) return 0.0
        val inter = w.toDouble() * h
        val union = a.width.toDouble() * a.height + b.width.toDouble() * b.height - inter
        return if (union <= 0) 0.0 else inter / union
    }

    private fun contains(b: Bounds, x: Int, y: Int) = x in b.left until b.right && y in b.top until b.bottom
}
