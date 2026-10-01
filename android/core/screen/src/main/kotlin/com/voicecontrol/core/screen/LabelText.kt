package com.voicecontrol.core.screen

/** Text helpers shared by label extraction, stable IDs and signatures. */
object LabelText {
    private val whitespace = Regex("\\s+")
    private val trailingMarks = Regex("[\\s*:：•\\-]+$")
    private val leadingMarks = Regex("^[\\s*•\\-]+")
    private val nonWord = Regex("[^\\p{L}\\p{M}\\p{N} ]")
    private val digits = Regex("\\p{N}+")

    /** Clean a label for display/speech: trims, collapses whitespace, drops "*" and ":" decorations. */
    fun clean(raw: String?): String? {
        if (raw == null) return null
        val cleaned = raw.replace(whitespace, " ").replace(trailingMarks, "").replace(leadingMarks, "").trim()
        return cleaned.takeIf { it.isNotEmpty() }
    }

    /** Aggressive normalization for comparisons: lowercase letters/spaces only, digits removed. */
    fun normalize(raw: String?): String =
        (raw ?: "").lowercase().replace(nonWord, " ").replace(digits, " ").replace(whitespace, " ").trim()

    /** "com.app:id/et_first_name" → "First name". */
    fun humanizeViewId(viewId: String?): String? {
        if (viewId.isNullOrBlank()) return null
        val entry = viewId.substringAfterLast('/').substringAfterLast(':')
        val words = entry
            .replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .replace('_', ' ')
            .replace('-', ' ')
            .lowercase()
            .split(' ')
            .filter { it.isNotBlank() && it !in noiseWords }
        if (words.isEmpty()) return null
        return words.joinToString(" ").replaceFirstChar { it.uppercase() }
    }

    /** FNV-1a 32-bit hash rendered as 8 hex chars: deterministic across JVMs and devices. */
    fun shortHash(input: String): String {
        var hash = 0x811c9dc5.toInt()
        for (byte in input.encodeToByteArray()) {
            hash = hash xor (byte.toInt() and 0xff)
            hash *= 0x01000193
        }
        return "%08x".format(hash)
    }

    private val noiseWords = setOf(
        "et", "edt", "edittext", "edit", "txt", "tv", "input", "field", "btn", "button", "ib", "til", "layout", "view", "id",
    )
}
