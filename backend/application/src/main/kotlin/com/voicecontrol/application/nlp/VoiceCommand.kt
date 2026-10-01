// Mirrors android/core/nlp so the offline "rules" provider understands speech exactly like the phone.
package com.voicecontrol.application.nlp

/** Navigation / control commands recognized locally (no network needed). */
sealed interface VoiceCommand {
    data object Next : VoiceCommand
    data object Previous : VoiceCommand
    data object Skip : VoiceCommand
    data object Submit : VoiceCommand
    data object Back : VoiceCommand
    data object ScrollDown : VoiceCommand
    data object ScrollUp : VoiceCommand
    data object Repeat : VoiceCommand
    data object Stop : VoiceCommand
    data object Yes : VoiceCommand
    data object No : VoiceCommand
    data object Clear : VoiceCommand
    data object Help : VoiceCommand
    /** Revert the last fill, toggle or button press. */
    data object Undo : VoiceCommand
    /** Screen reader: read the whole screen (or everything from the current item). */
    data object ReadScreen : VoiceCommand
    /** "press Login", "Login dabao", "लॉगिन पर क्लिक करो". */
    data class Press(val target: String) : VoiceCommand
}
