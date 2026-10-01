package com.voicecontrol.core.engine

import kotlin.math.max

/**
 * Decides, frame by frame, when someone starts talking over the assistant (barge-in).
 *
 * The first [calibrationFrames] measure the background level, which includes whatever echo of the
 * assistant's own voice survives echo cancellation. Speech is a run of [onsetFrames] frames clearly
 * louder than that ([ratio] × background, at least [minLevel]); short spikes (a cough, a click) decay away.
 */
class SpeechOnset(
    private val calibrationFrames: Int = 20,
    private val onsetFrames: Int = 12,
    private val ratio: Double = 3.0,
    private val minLevel: Double = 700.0,
) {
    private var frames = 0
    private var background = 0.0
    private var loud = 0

    /** Feed one frame's RMS level; returns true once speech has started. */
    fun onFrame(rms: Double): Boolean {
        frames++
        if (frames <= calibrationFrames) {
            background = max(background, rms)
            return false
        }
        val threshold = max(minLevel, background * ratio)
        loud = if (rms > threshold) loud + 1 else max(0, loud - 2)
        return loud >= onsetFrames
    }
}
