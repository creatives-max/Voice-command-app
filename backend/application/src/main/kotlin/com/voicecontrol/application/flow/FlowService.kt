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
            val normalized = steps.sortedBy { it.order }.mapIndexed { index, s ->
                val n = index + 1
                if (s.id.isBlank() || !ids.add(s.id)) throw DomainException.Validation("Step ids must be unique and non-empty")
                if (s.action.targetsElement && s.elementId.isBlank()) throw DomainException.Validation("Step $n: elementId is required")
                if (s.label.length > 200) throw DomainException.Validation("Step $n: label is too long")
                if ((s.question?.length ?: 0) > 500) throw DomainException.Validation("Step $n: question is too long")
                if ((s.defaultValue?.length ?: 0) > 500) throw DomainException.Validation("Step $n: default value is too long")
                val sensitive = s.fieldType?.isSensitive == true
                if (sensitive && !s.defaultValue.isNullOrBlank()) {
                    throw DomainException.Validation("Step $n: password/OTP/PIN fields cannot have a default value")
                }
                if (s.action == StepAction.CLICK && !s.defaultValue.isNullOrBlank()) {
                    throw DomainException.Validation("Step $n: button steps cannot have a default value")
                }
                s.rules.forEach { validateRule(it, n) }
                s.helpVideoUrl?.takeIf { it.isNotBlank() }?.let { validateUrl(it, n) }
                validateLogic(s, n, sensitive)
                s.copy(
                    order = index,
                    question = s.question?.trim()?.takeIf { it.isNotEmpty() },
                    defaultValue = s.defaultValue?.trim()?.takeIf { it.isNotEmpty() },
                    helpVideoUrl = s.helpVideoUrl?.trim()?.takeIf { it.isNotEmpty() },
                    rules = s.rules.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
                    condition = s.condition?.trim()?.takeIf { it.isNotEmpty() },
                    elseValue = s.elseValue?.trim()?.takeIf { it.isNotEmpty() },
                    variable = s.variable?.trim()?.takeIf { it.isNotEmpty() },
                    valueExpression = s.valueExpression?.trim()?.takeIf { it.isNotEmpty() },
                    appPackage = s.appPackage?.trim()?.takeIf { it.isNotEmpty() },
                    repeat = if (s.action == StepAction.REPEAT) s.repeat else null,
                )
            }
            validateRepeats(normalized)
            return normalized
        }

        private val variableRegex = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

        /** Expression syntax, variable names and the requirements of each logic step. */
        private fun validateLogic(s: FlowStep, n: Int, sensitive: Boolean) {
            fun expr(value: String?, what: String) {
                val v = value?.trim()?.takeIf { it.isNotEmpty() } ?: return
                if (v.length > MAX_EXPRESSION) throw DomainException.Validation("Step $n: $what is too long")
                Expressions.validate(v)?.let { throw DomainException.Validation("Step $n: $what: $it") }
            }
            expr(s.condition, "condition")
            expr(s.elseValue, "else value")
            expr(s.valueExpression, "computed value")
            expr(s.repeat?.countExpression, "repeat count")
            s.question?.let { q ->
                Regex("\\{([^{}]+)}").findAll(q).forEach { m ->
                    Expressions.validate(m.groupValues[1])?.let { throw DomainException.Validation("Step $n: question placeholder {${m.groupValues[1]}}: $it") }
                }
            }
            s.variable?.trim()?.takeIf { it.isNotEmpty() }?.let { v ->
                if (!variableRegex.matches(v) || v.length > 60) throw DomainException.Validation("Step $n: variable names use letters, digits and _")
                if (v == "index") throw DomainException.Validation("Step $n: 'index' is reserved for loops")
            }
            if (!s.elseValue.isNullOrBlank() && s.condition.isNullOrBlank()) {
                throw DomainException.Validation("Step $n: an else value needs a condition")
            }
            if (sensitive && (!s.valueExpression.isNullOrBlank() || !s.elseValue.isNullOrBlank())) {
                throw DomainException.Validation("Step $n: password/OTP/PIN fields cannot be filled automatically")
            }
            s.waitSeconds?.let { if (it !in 1..120) throw DomainException.Validation("Step $n: wait must be 1-120 seconds") }
            s.appPackage?.trim()?.takeIf { it.isNotEmpty() }?.let { validatePackage(it) }
            when (s.action) {
                StepAction.SET_VARIABLE -> if (s.variable.isNullOrBlank() || s.valueExpression.isNullOrBlank()) {
                    throw DomainException.Validation("Step $n: set-variable steps need a variable name and a value")
                }
                StepAction.OPEN_APP -> if (s.appPackage.isNullOrBlank()) throw DomainException.Validation("Step $n: open-app steps need an app package")
                StepAction.REPEAT -> {
                    val r = s.repeat ?: throw DomainException.Validation("Step $n: repeat steps need the steps to repeat")
                    if (r.stepIds.isEmpty()) throw DomainException.Validation("Step $n: choose at least one step to repeat")
                    if (r.maxIterations !in 1..50) throw DomainException.Validation("Step $n: repeat at most 1-50 times")
                    if ((r.addMoreLabel?.length ?: 0) > 200 || (r.itemLabel?.length ?: 0) > 60) {
                        throw DomainException.Validation("Step $n: repeat labels are too long")
                    }
                }
                else -> Unit
            }
        }

        /** Repeated steps must exist, sit on the same screen, not be logic boundaries, and belong to one loop. */
        private fun validateRepeats(steps: List<FlowStep>) {
            val byId = steps.associateBy { it.id }
            val screenOf = HashMap<String, Int>()
            var screen = 0
            steps.forEachIndexed { i, s ->
                if (s.action.isScreenBoundary && i > 0) screen++
                screenOf[s.id] = screen
            }
            val owner = HashMap<String, String>()
            steps.filter { it.action == StepAction.REPEAT }.forEach { loop ->
                val n = loop.order + 1
                loop.repeat!!.stepIds.forEach { id ->
                    val target = byId[id] ?: throw DomainException.Validation("Step $n: repeats a step that does not exist")
                    if (target.action == StepAction.REPEAT || target.action.isScreenBoundary) {
                        throw DomainException.Validation("Step $n: loops can't contain other loops or screen changes")
                    }
                    if (screenOf[id] != screenOf[loop.id]) throw DomainException.Validation("Step $n: repeated steps must be on the same screen")
                    owner.put(id, loop.id)?.let { throw DomainException.Validation("Step $n: a step can belong to only one loop") }
                }
            }
        }

        const val MAX_EXPRESSION = 500

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
