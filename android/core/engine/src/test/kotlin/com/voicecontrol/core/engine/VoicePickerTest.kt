package com.voicecontrol.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VoicePickerTest {
    private fun voice(name: String, lang: String, country: String, quality: Int, latency: Int = 300, network: Boolean = false, missing: Boolean = false) =
        VoiceCandidate(name, lang, country, quality, latency, network, missing)

    @Test
    fun `prefers the best installed voice of the language and country`() {
        val voices = listOf(
            voice("hi-in-basic", "hi", "IN", 300),
            voice("hi-in-x-hia-local", "hi", "IN", 400),
            voice("hi-in-x-hid-network", "hi", "IN", 500, network = true),
            voice("hi-in-x-hie-local", "hi", "IN", 500, missing = true),
            voice("en-in-x-ene-local", "en", "IN", 500),
        )
        assertEquals("hi-in-x-hia-local", VoicePicker.best(voices, "hi-IN")?.name)
    }

    @Test
    fun `country match beats quality, lower latency breaks ties, 3-letter codes count`() {
        val english = listOf(voice("en-us-best", "en", "US", 500), voice("en-in-good", "en", "IN", 400))
        assertEquals("en-in-good", VoicePicker.best(english, "en-IN")?.name)
        assertEquals("en-us-best", VoicePicker.best(english, "en")?.name)

        val tie = listOf(voice("slow", "ta", "IN", 400, latency = 400), voice("fast", "ta", "IN", 400, latency = 200))
        assertEquals("fast", VoicePicker.best(tie, "ta-IN")?.name)

        assertEquals("gu-3", VoicePicker.best(listOf(voice("gu-3", "guj", "IND", 400)), "gu-IN")?.name)
    }

    @Test
    fun `nothing usable means the engine keeps its default`() {
        assertNull(VoicePicker.best(listOf(voice("mr-net", "mr", "IN", 500, network = true)), "mr-IN"))
        assertNull(VoicePicker.best(emptyList(), "bn-IN"))
    }
}
