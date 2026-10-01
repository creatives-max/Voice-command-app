package com.voicecontrol.feature.assistant.overlay

import com.voicecontrol.core.model.ScreenElement

/** What the floating bubble is doing; drives its color, icon and animation. */
enum class BubbleMode { IDLE, LISTENING, SPEAKING, THINKING, ACTING, ERROR }

data class OverlayUiState(
    val visible: Boolean = true,
    val mode: BubbleMode = BubbleMode.IDLE,
    val sessionActive: Boolean = false,
    /** Main line shown next to the bubble (the current question or status). */
    val caption: String? = null,
    /** What the recognizer heard (partial or final). */
    val heard: String? = null,
    /** e.g. "2 / 5" while filling a form. */
    val progress: String? = null,
    val helpVideoUrl: String? = null,
    val panelOpen: Boolean = false,
    val panelElements: List<ScreenElement> = emptyList(),
    val micLevel: Float = 0f,
    /** "Teach by doing" is recording what the user does by touch. */
    val teaching: Boolean = false,
    /** Actions recorded so far while teaching. */
    val taughtSteps: Int = 0,
)
