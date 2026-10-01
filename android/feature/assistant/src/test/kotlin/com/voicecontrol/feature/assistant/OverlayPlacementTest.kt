package com.voicecontrol.feature.assistant

import com.voicecontrol.feature.assistant.overlay.OverlayPlacement
import com.voicecontrol.feature.assistant.overlay.WindowPlacement
import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayPlacementTest {
    private val w = 1080
    private val h = 2400
    private val mic = 168

    @Test
    fun `a wide panel opens to the left of a mic on the right, keeping the mic in place`() {
        // Only the mic: the window is the mic.
        assertEquals(WindowPlacement(860, 1300, alignStart = false), OverlayPlacement.place(860, 1300, mic, mic, mic, w, h))
        // Caption 740 px wide and 300 px tall above the mic: it grows left and up.
        val p = OverlayPlacement.place(860, 1300, mic, 740, mic + 300, w, h)
        assertEquals(WindowPlacement(860 + mic - 740, 1300 - 300, alignStart = false), p)
        // The mic stays at the window's bottom end: x + width - mic = 860.
        assertEquals(860, p.x + 740 - mic)
    }

    @Test
    fun `a mic on the left opens to the right`() {
        assertEquals(WindowPlacement(40, 1000, alignStart = true), OverlayPlacement.place(40, 1300, mic, 740, mic + 300, w, h))
    }

    @Test
    fun `nothing goes off screen`() {
        // A tall panel near the top is pushed down instead of disappearing above the screen.
        assertEquals(0, OverlayPlacement.place(860, 100, mic, 740, 900, w, h).y)
        // A panel wider than the space beside the mic is pushed back inside.
        assertEquals(860 + mic - 1000, OverlayPlacement.place(860, 1300, mic, 1000, mic, w, h).x)
        assertEquals(0, OverlayPlacement.place(100, 1300, mic, 1200, mic, w, h).x)
        // A mic dragged past the edge comes back.
        assertEquals((w - mic) to (h - mic), OverlayPlacement.clampMic(5000, 9000, mic, w, h))
        assertEquals(0 to 0, OverlayPlacement.clampMic(-50, -50, mic, w, h))
    }
}
