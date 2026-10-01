package com.voicecontrol.core.engine.port

import com.voicecontrol.core.model.Language

/** User preferences that shape a voice session. */
data class SessionConfig(
    val language: Language = Language.ENGLISH,
    val speechRate: Float = 1f,
    /** Type Devanagari answers in Latin letters for name/email/address fields. */
    val transliterate: Boolean = true,
    /** Read back filled values so mistakes can be corrected. */
    val confirmValues: Boolean = true,
    /** Ask before pressing the submit button at the end of a form. */
    val askBeforeSubmit: Boolean = true,
    /** Keep everything on the device (no backend interpretation or flow matching). */
    val localOnly: Boolean = false,
    /** Skip fields that already have a value instead of asking whether to keep it. */
    val skipFilledFields: Boolean = false,
    /** Use a screenshot + backend vision model when the app exposes no readable fields. */
    val visionFallback: Boolean = false,
    /** Stop talking as soon as the user starts speaking. */
    val bargeIn: Boolean = false,
    /** Ask "Did you say …?" when the recognizer is unsure. */
    val confirmLowConfidence: Boolean = true,
    /** Ask before pressing buttons that can't be undone (pay, delete, sign out…). */
    val confirmDestructive: Boolean = true,
)

fun interface SessionConfigProvider {
    suspend fun current(): SessionConfig
}
