package com.voicecontrol.core.engine

/** A text-to-speech voice as the phone describes it. */
data class VoiceCandidate(
    val name: String,
    /** ISO 639 language ("hi", "en", or 3-letter "hin"). */
    val language: String,
    /** ISO 3166 country ("IN"), may be blank. */
    val country: String,
    /** Android's scale: 100 (very low) to 500 (very high). */
    val quality: Int,
    /** Android's scale: 100 (very low latency) to 500 (very high latency). */
    val latency: Int,
    val needsNetwork: Boolean,
    /** The engine knows the voice but its data isn't downloaded. */
    val notInstalled: Boolean,
)

/**
 * Picks the best-sounding installed voice for a language: the phone's default voice is often a basic
 * one even when a better voice for the same language is installed. Voices that need the internet are
 * left out (they fail or lag on a weak connection, in the middle of a conversation).
 */
object VoicePicker {
    fun best(voices: List<VoiceCandidate>, languageTag: String): VoiceCandidate? {
        val parts = languageTag.replace('_', '-').split('-')
        val language = parts.first().lowercase()
        val country = parts.getOrNull(1)?.uppercase().orEmpty()
        return voices
            .filter { !it.notInstalled && !it.needsNetwork && sameLanguage(it.language, language) }
            .maxWithOrNull(
                compareBy<VoiceCandidate> { country.isNotEmpty() && it.country.equals(country, ignoreCase = true) }
                    .thenBy { it.quality }
                    .thenByDescending { it.latency }
                    .thenByDescending { it.name },
            )
    }

    private fun sameLanguage(voice: String, wanted: String): Boolean {
        val v = voice.lowercase()
        return v == wanted || ISO3[wanted] == v
    }

    private val ISO3 = mapOf(
        "en" to "eng", "hi" to "hin", "mr" to "mar", "ta" to "tam", "te" to "tel", "bn" to "ben", "gu" to "guj",
    )
}
