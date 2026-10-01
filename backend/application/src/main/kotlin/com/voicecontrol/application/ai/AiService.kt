package com.voicecontrol.application.ai

import com.voicecontrol.domain.ai.ElementKind
import com.voicecontrol.domain.ai.FieldQuestion
import com.voicecontrol.domain.ai.IntentKind
import com.voicecontrol.domain.ai.InterpretCommand
import com.voicecontrol.domain.ai.Interpretation
import com.voicecontrol.domain.ai.LlmProvider
import com.voicecontrol.domain.ai.QuestionsCommand
import com.voicecontrol.domain.ai.QuestionsResult
import com.voicecontrol.domain.ai.ScreenContext
import com.voicecontrol.domain.ai.VisionCommand
import com.voicecontrol.domain.ai.VisionResult
import com.voicecontrol.domain.common.DomainException
import com.voicecontrol.domain.event.Cache
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
    /** Written questions are cached per screen and language, so each screen costs one model call. */
    private val cache: Cache? = null,
) {
    private val log = LoggerFactory.getLogger(AiService::class.java)

    val providerName: String get() = provider.name

    suspend fun interpret(command: InterpretCommand): Interpretation {
        validate(command)
        val safe = command.copy(
            screen = command.screen.redacted(),
            utterance = command.utterance.trim(),
            // Defense in depth: the phone never sends secrets as memory, but drop anything labelled like one.
            memory = command.memory.filterNot { sensitiveLabel.containsMatchIn(it.label) }.takeLast(MAX_MEMORY),
        )
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

    /**
     * Friendly spoken questions (and a short explanation) for the screen's fields, in the user's language.
     * Sensitive fields (password, OTP, PIN) are left out. Empty when the provider can't write them or fails;
     * the phone then uses its built-in questions.
     */
    suspend fun writeQuestions(command: QuestionsCommand): QuestionsResult {
        if (command.screen.elements.size > MAX_ELEMENTS) throw DomainException.Validation("too many screen elements")
        val fields = command.screen.elements.filter { it.kind != ElementKind.BUTTON && it.kind != ElementKind.LINK && !it.sensitive }
            .take(MAX_QUESTION_FIELDS)
        if (fields.isEmpty() || provider.name == RulesInterpreter.SOURCE) return QuestionsResult(emptyList(), provider.name)
        val safe = command.copy(screen = command.screen.copy(elements = fields.map { it.copy(value = null) }))
        val key = questionsKey(safe)
        cache?.get(key)?.let { cached ->
            runCatching { cacheJson.decodeFromString<List<FieldQuestion>>(cached) }.getOrNull()?.let { return QuestionsResult(it, provider.name) }
        }
        val written = try {
            withTimeout(timeoutMillis * 2) { provider.writeQuestions(safe) }
        } catch (e: TimeoutCancellationException) {
            log.warn("LLM provider {} timed out writing questions", provider.name)
            return QuestionsResult(emptyList(), provider.name)
        } catch (e: Exception) {
            log.warn("LLM provider {} failed writing questions: {}", provider.name, e.message)
            return QuestionsResult(emptyList(), provider.name)
        }
        val ids = fields.map { it.id }.toSet()
        val clean = written
            .filter { it.elementId in ids && it.question.isNotBlank() }
            .distinctBy { it.elementId }
            .map { q ->
                FieldQuestion(q.elementId, q.question.trim().take(MAX_QUESTION_CHARS), q.hint?.trim()?.take(MAX_QUESTION_CHARS)?.takeIf { it.isNotEmpty() })
            }
        if (clean.isNotEmpty()) cache?.put(key, cacheJson.encodeToString(clean), QUESTIONS_TTL_SECONDS)
        return QuestionsResult(clean, provider.name)
    }

    private fun questionsKey(c: QuestionsCommand): String {
        val text = buildString {
            append(c.language.name).append('|').append(c.screen.packageName)
            c.screen.elements.forEach { e -> append('|').append(e.id).append('~').append(e.kind).append('~').append(e.label).append('~').append(e.fieldType).append('~').append(e.hint) }
        }
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
        return "vc:questions:v1:$digest"
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
        const val MAX_MEMORY = 12
        const val MAX_QUESTION_FIELDS = 40
        const val MAX_QUESTION_CHARS = 200
        const val QUESTIONS_TTL_SECONDS = 30L * 24 * 3600
        private val cacheJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        private val sensitiveLabel = Regex("(?i)pass|otp|\\bpin\\b|cvv|cvc|card number|पासवर्ड|ओटीपी")
    }
}
