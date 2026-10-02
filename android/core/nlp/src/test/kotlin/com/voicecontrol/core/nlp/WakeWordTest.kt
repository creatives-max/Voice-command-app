package com.voicecontrol.core.nlp

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WakeWordTest {
    @Test
    fun `the greeting may be dropped or misheard`() {
        listOf("hey voice control", "a voice control", "voice control", "hay voicecontrol", "ok voice control please").forEach {
            assertTrue(WakeWord.matches(it, "hey voice control"), it)
        }
        assertTrue(WakeWord.matches("वॉइस कंट्रोल", "hey voice control"))
    }

    @Test
    fun `other speech does not wake`() {
        listOf("hey", "what is the weather", "voice", "hey google", "choice of control").forEach {
            assertFalse(WakeWord.matches(it, "hey voice control"), it)
        }
        // A short core word alone is not enough: "hey siri" needs the greeting.
        assertFalse(WakeWord.matches("siri", "hey siri"))
    }
}
