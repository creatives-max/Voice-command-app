package com.voicecontrol.core.screen

import com.voicecontrol.core.model.ScreenSnapshot

/**
 * Builds a compact, order-independent text describing a screen's structure.
 *
 * Example: `com.shop.app|CheckoutActivity|button:place order|text_field/email:email|text_field/name:full name`
 *
 * The same text is embedded server-side (pgvector) to match a live screen to the closest saved flow,
 * so small label changes ("Mobile no." → "Mobile number") still land near each other.
 */
object ScreenSignature {
    fun of(snapshot: ScreenSnapshot): String {
        val parts = snapshot.elements.map { e ->
            val type = e.fieldType?.let { "/${it.name.lowercase()}" } ?: ""
            "${e.kind.name.lowercase()}$type:${LabelText.normalize(e.label)}"
        }.distinct().sorted()
        val activity = snapshot.activityName?.substringAfterLast('.') ?: ""
        return (listOf(snapshot.packageName, activity) + parts).joinToString("|")
    }

    /** Short stable hash for cache keys. */
    fun hash(signature: String): String = LabelText.shortHash(signature)
}
