package com.voicecontrol.core.engine

import com.voicecontrol.core.engine.port.InterpretRequest
import com.voicecontrol.core.engine.port.Interpreter
import com.voicecontrol.core.model.IntentKind
import com.voicecontrol.core.model.Interpretation
import com.voicecontrol.core.nlp.CommandParser
import com.voicecontrol.core.nlp.SpeechNormalizer
import com.voicecontrol.core.nlp.VoiceCommand

/**
 * Offline interpreter: commands via [CommandParser], button presses via [ButtonMatcher],
 * everything else is an answer for the current field, normalized by [SpeechNormalizer].
 */
class LocalInterpreter : Interpreter {

    override suspend fun interpret(request: InterpretRequest): Interpretation {
        commandOf(request)?.let { return it }
        val field = request.currentFieldId?.let(request.screen::element)
            ?: return Interpretation(IntentKind.UNKNOWN, source = SOURCE)
        val value = SpeechNormalizer.normalize(request.utterance, field.fieldType, request.transliterate)
        if (value.isBlank()) return Interpretation(IntentKind.UNKNOWN, source = SOURCE)
        return Interpretation(IntentKind.FILL, targetId = field.id, value = value, source = SOURCE)
    }

    /** Returns an interpretation when the utterance is a control command, else null. */
    fun commandOf(request: InterpretRequest): Interpretation? {
        val command = CommandParser.parse(request.utterance) ?: return null
        val intent = when (command) {
            VoiceCommand.Next -> IntentKind.NEXT
            VoiceCommand.Previous -> IntentKind.PREVIOUS
            VoiceCommand.Skip -> IntentKind.SKIP
            VoiceCommand.Submit -> IntentKind.SUBMIT
            VoiceCommand.Back -> IntentKind.BACK
            VoiceCommand.ScrollDown -> IntentKind.SCROLL_DOWN
            VoiceCommand.ScrollUp -> IntentKind.SCROLL_UP
            VoiceCommand.Repeat -> IntentKind.REPEAT
            VoiceCommand.Stop -> IntentKind.STOP
            VoiceCommand.Yes -> IntentKind.YES
            VoiceCommand.No -> IntentKind.NO
            VoiceCommand.Clear -> IntentKind.CLEAR
            VoiceCommand.Help -> IntentKind.HELP
            is VoiceCommand.Press -> {
                val button = ButtonMatcher.find(command.target, request.screen.elements)
                    ?: return Interpretation(IntentKind.CLICK, targetId = null, value = command.target, source = SOURCE)
                return Interpretation(IntentKind.CLICK, targetId = button.id, source = SOURCE)
            }
        }
        return Interpretation(intent, source = SOURCE)
    }

    companion object {
        const val SOURCE = "local"
    }
}
