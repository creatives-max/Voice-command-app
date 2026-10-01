package com.voicecontrol.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ScrollDirection { UP, DOWN }

/** Something VoiceControl can do to the foreground app. */
@Serializable
sealed interface ScreenAction {
    @Serializable @SerialName("set_text")
    data class SetText(val elementId: String, val text: String) : ScreenAction

    @Serializable @SerialName("click")
    data class Click(val elementId: String) : ScreenAction

    @Serializable @SerialName("set_checked")
    data class SetChecked(val elementId: String, val checked: Boolean) : ScreenAction

    @Serializable @SerialName("focus")
    data class Focus(val elementId: String) : ScreenAction

    @Serializable @SerialName("scroll")
    data class Scroll(val direction: ScrollDirection) : ScreenAction

    @Serializable @SerialName("back")
    data object Back : ScreenAction

    /** Tap absolute screen coordinates (vision fallback for apps without accessible nodes). */
    @Serializable @SerialName("tap")
    data class TapAt(val x: Int, val y: Int) : ScreenAction

    /** Type into whichever input currently has focus (vision fallback after [TapAt]). */
    @Serializable @SerialName("type_focused")
    data class TypeIntoFocused(val text: String) : ScreenAction
}

sealed interface ActionResult {
    data object Success : ActionResult
    data class Failure(val reason: String) : ActionResult

    val isSuccess: Boolean get() = this is Success
}

/**
 * A downscaled JPEG of the screen. Coordinates returned by vision models are in image space;
 * [toScreenX]/[toScreenY] map them back to real screen pixels.
 */
class Screenshot(
    val jpeg: ByteArray,
    val width: Int,
    val height: Int,
    val screenWidth: Int,
    val screenHeight: Int,
) {
    fun toScreenX(imageX: Int): Int = if (width == 0) imageX else imageX * screenWidth / width
    fun toScreenY(imageY: Int): Int = if (height == 0) imageY else imageY * screenHeight / height
}
