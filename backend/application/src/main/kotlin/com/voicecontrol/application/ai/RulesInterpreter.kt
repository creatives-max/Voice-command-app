package com.voicecontrol.application.ai

import com.voicecontrol.application.nlp.CommandParser
import com.voicecontrol.application.nlp.SpeechNormalizer
import com.voicecontrol.application.nlp.VoiceCommand
import com.voicecontrol.domain.ai.IntentKind
import com.voicecontrol.domain.ai.InterpretCommand
import com.voicecontrol.domain.ai.Interpretation

/** Deterministic, offline interpretation: commands + per-field normalization. */
class RulesInterpreter {

    fun commandOf(command: InterpretCommand): Interpretation? {
        val parsed = CommandParser.parse(command.utterance) ?: return null
        val intent = when (parsed) {
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
            VoiceCommand.Undo -> IntentKind.UNDO
            VoiceCommand.ReadScreen -> IntentKind.READ_SCREEN
            is VoiceCommand.Press -> {
                val button = ButtonMatcher.find(parsed.target, command.screen.elements)
                return Interpretation(IntentKind.CLICK, targetId = button?.id, value = if (button == null) parsed.target else null, source = SOURCE)
            }
        }
        return Interpretation(intent, source = SOURCE)
    }

    fun interpret(command: InterpretCommand): Interpretation {
        commandOf(command)?.let { return it }
        val field = command.screen.element(command.currentFieldId)?.takeIf { !it.sensitive }
            ?: return Interpretation(IntentKind.UNKNOWN, source = SOURCE)
        val value = SpeechNormalizer.normalize(command.utterance, field.fieldType, command.transliterate)
        if (value.isBlank()) return Interpretation(IntentKind.UNKNOWN, source = SOURCE)
        return Interpretation(IntentKind.FILL, targetId = field.id, value = value, source = SOURCE)
    }

    companion object {
        const val SOURCE = "rules"
    }
}
