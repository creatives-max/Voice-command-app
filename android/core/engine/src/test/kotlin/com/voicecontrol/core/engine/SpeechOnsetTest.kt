package com.voicecontrol.core.engine

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpeechOnsetTest {
    private fun run(levels: List<Double>): Int? {
        val onset = SpeechOnset()
        levels.forEachIndexed { i, l -> if (onset.onFrame(l)) return i }
        return null
    }

    @Test
    fun `sustained speech over the echo level triggers, echo and clicks do not`() {
        val echo = List(20) { 900.0 }
        assertFalse(run(echo + List(100) { 1_200.0 }) != null, "TTS echo alone")
        assertFalse(run(echo + List(5) { 8_000.0 } + List(50) { 900.0 }) != null, "a click")
        val at = run(echo + List(30) { 4_000.0 })
        assertTrue(at != null && at in 30..33, "speech after ~240 ms, got $at")
        // Quiet room: absolute floor applies.
        assertTrue(run(List(20) { 50.0 } + List(20) { 1_500.0 }) != null)
        assertFalse(run(List(20) { 50.0 } + List(40) { 400.0 }) != null)
    }
}
