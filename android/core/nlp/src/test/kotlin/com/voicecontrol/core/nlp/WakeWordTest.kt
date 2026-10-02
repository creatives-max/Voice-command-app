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

    @Test
    fun `a request in the same breath is kept`() {
        kotlin.test.assertEquals("YouTube kholo", WakeWord.after("hey voice control, YouTube kholo", "hey voice control"))
        kotlin.test.assertEquals("mujhe bijli ka bill bharna hai", WakeWord.after("voice control zara mujhe bijli ka bill bharna hai", "hey voice control"))
        kotlin.test.assertEquals("time kya hua", WakeWord.after("वॉइस कंट्रोल time kya hua", "hey voice control"))
        kotlin.test.assertEquals(null, WakeWord.after("hey voice control", "hey voice control"))
        kotlin.test.assertEquals(null, WakeWord.after("open YouTube", "hey voice control"))
    }
}
