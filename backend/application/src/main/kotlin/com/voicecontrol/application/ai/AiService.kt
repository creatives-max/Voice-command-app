package com.voicecontrol.application.ai

import com.voicecontrol.domain.ai.IntentKind
import com.voicecontrol.domain.ai.InterpretCommand
import com.voicecontrol.domain.ai.Interpretation
import com.voicecontrol.domain.ai.LlmProvider
import com.voicecontrol.domain.ai.ScreenContext
import com.voicecontrol.domain.ai.VisionCommand
import com.voicecontrol.domain.ai.VisionResult
import com.voicecontrol.domain.common.DomainException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory

/**
 * Turns "screen + what the user said" into an action.
 *
 * 1. Commands (next/submit/…) are recognized deterministically — no LLM latency or cost.
 * 2. Everything else goes to the configured [LlmProvider] with a timeout.
 * 3. The model's answer is sanitized: unknown or sensitive targets are dropped.
 * 4. Any provider failure falls back to the offline rules so the user is never stuck.
 */
class AiService(
    private val provider: LlmProvider,
    private val rules: RulesInterpreter = RulesInterpreter(),
    private val timeoutMillis: Long = 8_000,
) {
    private val log = LoggerFactory.getLogger(AiService::class.java)

    val providerName: String get() = provider.name

    suspend fun interpret(command: InterpretCommand): Interpretation {
        validate(command)
        val safe = command.copy(screen = command.screen.redacted(), utterance = command.utterance.trim())
        rules.commandOf(safe)?.let { return it }
        if (provider.name == RulesInterpreter.SOURCE) return rules.interpret(safe)
        val raw = try {
            withTimeout(timeoutMillis) { provider.interpret(safe) }
        } catch (e: TimeoutCancellationException) {
            log.warn("LLM provider {} timed out, using rules", provider.name)
            return rules.interpret(safe).copy(source = "rules-fallback")
        } catch (e: Exception) {
            log.warn("LLM provider {} failed: {}", provider.name, e.message)
            return rules.interpret(safe).copy(source = "rules-fallback")
        }
        return sanitize(raw, safe.screen, safe)
    }

    suspend fun detectElements(command: VisionCommand): VisionResult {
        if (command.jpeg.isEmpty() || command.jpeg.size > MAX_IMAGE_BYTES) {
            throw DomainException.Validation("Screenshot must be between 1 byte and ${MAX_IMAGE_BYTES / 1_000_000} MB")
        }
        return try {
            withTimeout(timeoutMillis * 3) { provider.detectElements(command) }
        } catch (e: UnsupportedOperationException) {
            throw DomainException.Validation("The configured AI provider (${provider.name}) does not support vision")
        } catch (e: TimeoutCancellationException) {
            throw DomainException.Upstream("Vision request timed out")
        }
    }

    private fun validate(command: InterpretCommand) {
        if (command.utterance.isBlank()) throw DomainException.Validation("utterance must not be blank")
        if (command.utterance.length > MAX_UTTERANCE) throw DomainException.Validation("utterance is too long")
        if (command.screen.elements.size > MAX_ELEMENTS) throw DomainException.Validation("too many screen elements")
    }

    /** Never trust model output blindly: targets must exist on screen and must not be sensitive. */
    internal fun sanitize(result: Interpretation, screen: ScreenContext, command: InterpretCommand): Interpretation {
        val target = screen.element(result.targetId)
        val extras = result.extraFills.filter { fill -> screen.element(fill.targetId)?.let { !it.sensitive } == true && fill.value.isNotBlank() }
        return when (result.intent) {
            IntentKind.FILL -> {
                val fieldId = target?.takeIf { !it.sensitive }?.id ?: screen.element(command.currentFieldId)?.takeIf { !it.sensitive }?.id
                val value = result.value?.trim()
                if (fieldId == null || value.isNullOrEmpty()) {
                    rules.interpret(command).copy(source = "rules-fallback")
                } else {
                    result.copy(targetId = fieldId, value = value, extraFills = extras)
                }
            }
            IntentKind.CLICK -> if (target == null || target.sensitive) {
                result.copy(targetId = null)
            } else {
                result.copy(extraFills = emptyList())
            }
            else -> result.copy(targetId = null, extraFills = emptyList())
        }
    }

    companion object {
        const val MAX_UTTERANCE = 500
        const val MAX_ELEMENTS = 300
        const val MAX_IMAGE_BYTES = 4_000_000
    }
}
