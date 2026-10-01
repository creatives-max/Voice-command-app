package com.voicecontrol.application.flow

import com.voicecontrol.domain.common.DomainException
import com.voicecontrol.domain.event.EventPublisher
import com.voicecontrol.domain.event.FlowDeleted
import com.voicecontrol.domain.event.FlowVersionSaved
import com.voicecontrol.domain.flow.AppSummary
import com.voicecontrol.domain.flow.Flow
import com.voicecontrol.domain.flow.FlowRepository
import com.voicecontrol.domain.flow.FlowStep
import com.voicecontrol.domain.flow.FlowVersion
import com.voicecontrol.domain.flow.FlowWithVersion
import com.voicecontrol.domain.flow.StepAction
import com.voicecontrol.domain.flow.VersionSource
import java.net.URI
import java.time.Clock
import java.util.UUID

data class NewFlow(val appPackage: String, val name: String, val screenSignature: String, val steps: List<FlowStep>)

/**
 * Versioned flow library. Every edit (from the phone or the dashboard) appends an immutable version;
 * the flow points at its current version, so rollbacks are just new versions copying old steps.
 * Each saved version is published as a [FlowVersionSaved] event for asynchronous processing.
 */
class FlowService(
    private val flows: FlowRepository,
    private val events: EventPublisher,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** Upload from the phone. Idempotent per (app, screen signature): returns the existing flow if any. */
    suspend fun createFromDevice(userId: UUID, input: NewFlow): FlowWithVersion {
        val appPackage = validatePackage(input.appPackage)
        val signature = input.screenSignature.trim().take(MAX_SIGNATURE).ifEmpty { throw DomainException.Validation("screenSignature is required") }
        flows.findBySignature(userId, appPackage, signature)?.let { return it }
        val now = clock.instant()
        val flow = Flow(UUID.randomUUID(), userId, appPackage, validateName(input.name), signature, 1, now, now)
        val version = FlowVersion(flow.id, 1, normalizeSteps(input.steps), signature, VersionSource.DEVICE, "Recorded on device", now)
        val created = flows.create(flow, version)
        publish(created)
        return created
    }

    suspend fun list(userId: UUID, appPackage: String?, limit: Int, offset: Int): List<Flow> =
        flows.list(userId, appPackage?.takeIf { it.isNotBlank() }, limit.coerceIn(1, 200), offset.coerceAtLeast(0))

    suspend fun apps(userId: UUID): List<AppSummary> = flows.apps(userId)

    suspend fun get(userId: UUID, flowId: UUID): FlowWithVersion =
        flows.find(userId, flowId) ?: throw DomainException.NotFound("Flow not found")

    suspend fun versions(userId: UUID, flowId: UUID): List<FlowVersion> {
        get(userId, flowId)
        return flows.versions(userId, flowId)
    }

    suspend fun version(userId: UUID, flowId: UUID, version: Int): FlowVersion =
        flows.version(userId, flowId, version) ?: throw DomainException.NotFound("Version not found")

    /** Dashboard edit: questions, rules, defaults, skips, order, help videos (and optionally the name). */
    suspend fun update(
        userId: UUID,
        flowId: UUID,
        expectedVersion: Int,
        name: String?,
        steps: List<FlowStep>,
        changeNote: String?,
        source: VersionSource = VersionSource.DASHBOARD,
    ): FlowWithVersion {
        val current = get(userId, flowId)
        val next = FlowVersion(
            flowId = flowId,
            version = current.flow.currentVersion + 1,
            steps = normalizeSteps(steps),
            screenSignature = current.flow.screenSignature,
            source = source,
            changeNote = changeNote?.trim()?.take(500)?.takeIf { it.isNotEmpty() },
            createdAt = clock.instant(),
        )
        val saved = flows.addVersion(userId, flowId, expectedVersion, name?.let(::validateName), next)
            ?: throw DomainException.Conflict("This flow was changed by someone else. Reload and try again.")
        publish(saved)
        return saved
    }

    suspend fun rollback(userId: UUID, flowId: UUID, toVersion: Int): FlowWithVersion {
        val current = get(userId, flowId)
        val old = version(userId, flowId, toVersion)
        return update(userId, flowId, current.flow.currentVersion, null, old.steps, "Rolled back to version $toVersion", VersionSource.ROLLBACK)
    }

    suspend fun delete(userId: UUID, flowId: UUID) {
        val existing = get(userId, flowId)
        if (!flows.delete(userId, flowId)) throw DomainException.NotFound("Flow not found")
        events.publish(FlowDeleted(flowId.toString(), userId.toString(), existing.flow.appPackage))
    }

    private suspend fun publish(saved: FlowWithVersion) {
        events.publish(
            FlowVersionSaved(
                flowId = saved.flow.id.toString(),
                userId = saved.flow.userId.toString(),
                appPackage = saved.flow.appPackage,
                version = saved.version.version,
                screenSignature = saved.version.screenSignature,
            ),
        )
    }

    companion object {
        const val MAX_STEPS = 100
        const val MAX_SIGNATURE = 4_000

        private val packageRegex = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$")

        fun validatePackage(p: String): String {
            val v = p.trim()
            if (v.length > 255 || !packageRegex.matches(v)) throw DomainException.Validation("Invalid app package name")
            return v
        }

        fun validateName(n: String): String {
            val v = n.trim()
            if (v.isEmpty() || v.length > 120) throw DomainException.Validation("Flow name must be 1-120 characters")
            return v
        }

        /** Validates and renumbers steps (order = list position), so the dashboard's drag order is authoritative. */
        fun normalizeSteps(steps: List<FlowStep>): List<FlowStep> {
            if (steps.size > MAX_STEPS) throw DomainException.Validation("A flow can have at most $MAX_STEPS steps")
            val ids = mutableSetOf<String>()
            return steps.sortedBy { it.order }.mapIndexed { index, s ->
                if (s.id.isBlank() || !ids.add(s.id)) throw DomainException.Validation("Step ids must be unique and non-empty")
                if (s.elementId.isBlank()) throw DomainException.Validation("Step ${index + 1}: elementId is required")
                if (s.label.length > 200) throw DomainException.Validation("Step ${index + 1}: label is too long")
                if ((s.question?.length ?: 0) > 500) throw DomainException.Validation("Step ${index + 1}: question is too long")
                if ((s.defaultValue?.length ?: 0) > 500) throw DomainException.Validation("Step ${index + 1}: default value is too long")
                if (s.fieldType?.isSensitive == true && !s.defaultValue.isNullOrBlank()) {
                    throw DomainException.Validation("Step ${index + 1}: password/OTP/PIN fields cannot have a default value")
                }
                if (s.action == StepAction.CLICK && !s.defaultValue.isNullOrBlank()) {
                    throw DomainException.Validation("Step ${index + 1}: button steps cannot have a default value")
                }
                s.rules.forEach { validateRule(it, index + 1) }
                s.helpVideoUrl?.takeIf { it.isNotBlank() }?.let { validateUrl(it, index + 1) }
                s.copy(
                    order = index,
                    question = s.question?.trim()?.takeIf { it.isNotEmpty() },
                    defaultValue = s.defaultValue?.trim()?.takeIf { it.isNotEmpty() },
                    helpVideoUrl = s.helpVideoUrl?.trim()?.takeIf { it.isNotEmpty() },
                    rules = s.rules.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
                )
            }
        }

        private val knownRules = setOf("required", "email", "phone", "pincode", "digits", "min", "max", "regex", "oneof")

        fun validateRule(rule: String, step: Int) {
            val name = rule.substringBefore(':').trim().lowercase()
            val arg = rule.substringAfter(':', "").trim()
            if (name.isEmpty()) return
            if (name !in knownRules) throw DomainException.Validation("Step $step: unknown rule '$name'")
            when (name) {
                "digits", "min", "max" -> if (arg.toIntOrNull()?.takeIf { it in 0..1000 } == null) {
                    throw DomainException.Validation("Step $step: rule '$name' needs a number")
                }
                "regex" -> if (arg.isEmpty() || arg.length > 300 || runCatching { Regex(arg) }.isFailure) {
                    throw DomainException.Validation("Step $step: invalid regular expression")
                }
                "oneof" -> if (arg.split('|').none { it.isNotBlank() }) throw DomainException.Validation("Step $step: oneOf needs options like a|b|c")
            }
        }

        private fun validateUrl(url: String, step: Int) {
            val uri = runCatching { URI(url.trim()) }.getOrNull()
            if (uri == null || uri.scheme?.lowercase() != "https" || uri.host.isNullOrBlank() || url.length > 2_000) {
                throw DomainException.Validation("Step $step: help video must be an https:// link")
            }
        }
    }
}
