package com.voicecontrol.feature.assistant.overlay

/** Where the overlay window goes so the mic stays put and everything stays on screen. */
internal data class WindowPlacement(val x: Int, val y: Int, val alignStart: Boolean)

/**
 * The mic bubble sits at the bottom of the overlay window, with the caption or button panel above it.
 * The window is placed so the mic keeps its spot ([micX], [micY] = its top-left corner) and the panel
 * opens toward the middle of the screen: to the right when the mic is on the left half, else to the left.
 * Nothing goes off screen: the mic is kept inside, and a panel that doesn't fit is pushed back in.
 */
internal object OverlayPlacement {
    fun clampMic(micX: Int, micY: Int, micSize: Int, screenWidth: Int, screenHeight: Int): Pair<Int, Int> =
        micX.coerceIn(0, (screenWidth - micSize).coerceAtLeast(0)) to micY.coerceIn(0, (screenHeight - micSize).coerceAtLeast(0))

    fun place(
        micX: Int,
        micY: Int,
        micSize: Int,
        windowWidth: Int,
        windowHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
    ): WindowPlacement {
        val alignStart = micX + micSize / 2 < screenWidth / 2
        val x = if (alignStart) micX else micX + micSize - windowWidth
        val y = micY + micSize - windowHeight
        return WindowPlacement(
            x = x.coerceIn(0, (screenWidth - windowWidth).coerceAtLeast(0)),
            y = y.coerceIn(0, (screenHeight - windowHeight).coerceAtLeast(0)),
            alignStart = alignStart,
        )
    }
}
