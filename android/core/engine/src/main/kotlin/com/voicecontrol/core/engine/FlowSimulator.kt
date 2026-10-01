package com.voicecontrol.core.engine

import com.voicecontrol.core.engine.expr.Expressions
import com.voicecontrol.core.model.ElementKind
import com.voicecontrol.core.model.FlowStep
import com.voicecontrol.core.model.ProfileKey
import com.voicecontrol.core.model.StepAction
import com.voicecontrol.core.nlp.FieldValidator
import java.time.LocalDate

/**
 * Dry run of a flow without touching any app: walks the steps the way a real run does (conditions,
 * else values, computed values, variables, templates, rules, loops, screen and app changes) and
 * answers each question from a script. When the script runs out, the result says which question is
 * pending, so a caller can ask the user and simulate again with one more answer.
 *
 * Same behaviour as the dashboard simulator (`dashboard/src/features/flows/simulator.ts`) and the
 * backend copy; all three run the shared vectors in `docs/spec/simulation.json`.
 */
object FlowSimulator {

    enum class EntryKind { SCREEN, ASK, ANSWER, FILL, SKIP, PRESS, VARIABLE, ERROR, MANUAL, DONE }

    data class Entry(val kind: EntryKind, val text: String, val stepId: String? = null)

    /** YESNO questions are answered with yes/no; TEXT with a value (or "skip"). */
    enum class Expects { TEXT, YESNO }

    data class Pending(val stepId: String, val question: String, val expects: Expects)

    data class Result(
        val transcript: List<Entry>,
        val vars: Map<String, String>,
        /** Final value of each element step, by step id. */
        val values: Map<String, String>,
        val pending: Pending?,
        val finished: Boolean,
    )

    const val MAX_ATTEMPTS = 3
    private val SKIP_WORDS = setOf("skip", "next", "chhodo", "aage", "छोड़ो", "आगे")
    private val NO_WORDS = setOf("no", "n", "nahi", "nahin", "na", "nope", "false", "0", "नहीं", "ना")

    /** Profile values by expression name (`profile.name`, `profile.first_name`…). */
    val PROFILE_VARIABLES: Map<ProfileKey, String> = mapOf(
        ProfileKey.FULL_NAME to "profile.name",
        ProfileKey.FIRST_NAME to "profile.first_name",
        ProfileKey.LAST_NAME to "profile.last_name",
        ProfileKey.EMAIL to "profile.email",
        ProfileKey.PHONE to "profile.phone",
        ProfileKey.ADDRESS_LINE to "profile.address",
        ProfileKey.CITY to "profile.city",
        ProfileKey.STATE to "profile.state",
        ProfileKey.PINCODE to "profile.pincode",
        ProfileKey.DATE_OF_BIRTH to "profile.dob",
    )

    /** The `profile` argument of [simulate], from a profile value getter. */
    fun profileVariables(value: (ProfileKey) -> String?): Map<String, String> =
        PROFILE_VARIABLES.mapNotNull { (key, name) -> value(key)?.trim()?.takeIf { it.isNotEmpty() }?.let { name to it } }.toMap()

    fun defaultQuestion(step: FlowStep): String = when {
        step.kind == ElementKind.CHECKBOX || step.kind == ElementKind.SWITCH || step.kind == ElementKind.RADIO ||
            step.action == StepAction.TOGGLE -> "Should I select \"${step.label}\"? Say yes or no."
        step.kind == ElementKind.DROPDOWN -> "Which option for ${step.label}?"
        step.fieldType?.name == "EMAIL" -> "What is your email address?"
        step.fieldType?.name == "PHONE" -> "What is your mobile number?"
        step.fieldType?.name == "DATE" -> "Please say the ${step.label}, like 12 March 1990."
        else -> "Please say ${step.label}."
    }

    /** Variable name of a step: [FlowStep.variable], else its label in snake case. */
    fun variableName(step: FlowStep): String {
        step.variable?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val s = step.label.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
        return when {
            s.isEmpty() -> "field_${step.order}"
            s.first().isDigit() -> "f_$s"
            else -> s
        }
    }

    /** Splits ordered steps into screens; each NEXT_SCREEN / OPEN_APP step starts a new one. */
    fun segments(steps: List<FlowStep>): List<List<FlowStep>> {
        val out = mutableListOf<MutableList<FlowStep>>(mutableListOf())
        steps.forEach { s ->
            if (s.action.isScreenBoundary && out.last().isNotEmpty()) out += mutableListOf<FlowStep>()
            out.last() += s
        }
        return out
    }

    /**
     * @param profile profile values by expression name, see [PROFILE_VARIABLES].
     * @param answers what the user says, in order.
     */
    fun simulate(
        steps: List<FlowStep>,
        answers: List<String>,
        profile: Map<String, String> = emptyMap(),
        today: () -> LocalDate = { LocalDate.now() },
    ): Result = Run(answers, profile, today).run(steps.sortedBy { it.order })

    private class NeedsInput(val pending: Pending) : RuntimeException(null, null, false, false)

    private class Run(val answers: List<String>, val profile: Map<String, String>, val today: () -> LocalDate) {
        val transcript = mutableListOf<Entry>()
        val vars = linkedMapOf<String, String>()
        val values = linkedMapOf<String, String>()
        var cursor = 0

        fun log(kind: EntryKind, text: String, stepId: String? = null) {
            transcript += Entry(kind, text, stepId)
        }

        val lookup: (String) -> Any? = { name -> vars[name] ?: profile[name]?.takeIf { it.isNotBlank() } }

        fun sensitive(s: FlowStep) = s.fieldType?.isSensitive == true

        fun remember(s: FlowStep, value: String) {
            if (sensitive(s)) return
            val name = variableName(s)
            vars[name] = value
            vars["index"]?.takeIf { it.isNotEmpty() }?.let { vars["${name}_$it"] = value }
        }

        fun <T> safe(fallback: T, f: () -> T): T = try {
            f()
        } catch (e: Exception) {
            fallback
        }

        fun ask(stepId: String, question: String, expects: Expects): String {
            if (cursor >= answers.size) throw NeedsInput(Pending(stepId, question, expects))
            log(EntryKind.ASK, question, stepId)
            val answer = answers[cursor++]
            log(EntryKind.ANSWER, answer, stepId)
            return answer
        }

        fun isYes(a: String) = Expressions.isYes(a)
        fun isNo(a: String) = a.trim().lowercase() in NO_WORDS
        fun isSkip(a: String) = a.trim().lowercase() in SKIP_WORDS

        fun fill(step: FlowStep, value: String, how: String) {
            values[step.id] = value
            remember(step, value)
            val prefix = when (how) {
                "computed" -> "Computed"
                "default" -> "Default"
                else -> "Filled"
            }
            log(EntryKind.FILL, "$prefix: ${step.label} = $value", step.id)
        }

        fun setToggle(step: FlowStep, checked: Boolean) {
            val v = if (checked) "yes" else "no"
            values[step.id] = v
            remember(step, v)
            log(EntryKind.FILL, "${if (checked) "Selected" else "Unselected"}: ${step.label}", step.id)
        }

        fun conditionHolds(step: FlowStep): Boolean {
            val c = step.condition?.takeIf { it.isNotBlank() } ?: return true
            return safe(true) { Expressions.evaluateBoolean(c, lookup, today) }
        }

        fun applyElse(step: FlowStep) {
            if (step.action != StepAction.FILL && step.action != StepAction.TOGGLE) {
                log(EntryKind.SKIP, "Skipped ${step.label.ifEmpty { step.action.name }} (condition false)", step.id)
                return
            }
            val value = step.elseValue?.takeIf { it.isNotBlank() }?.let { safe("") { Expressions.evaluateText(it, lookup, today) } }.orEmpty()
            if (value.isEmpty() || sensitive(step)) {
                log(EntryKind.SKIP, "Skipped ${step.label} (condition false)", step.id)
                return
            }
            if (step.action == StepAction.TOGGLE) setToggle(step, Expressions.truthy(value)) else fill(step, value, "computed")
        }

        fun runStep(step: FlowStep, loops: Map<String, List<FlowStep>>) {
            when (step.action) {
                StepAction.SET_VARIABLE -> {
                    val value = step.valueExpression?.takeIf { it.isNotBlank() }
                        ?.let { safe("") { Expressions.evaluateText(it, lookup, today) } }.orEmpty()
                    step.variable?.trim()?.takeIf { it.isNotEmpty() }?.let { name ->
                        vars[name] = value
                        log(EntryKind.VARIABLE, "$name = ${value.ifEmpty { "(empty)" }}", step.id)
                    }
                    return
                }
                StepAction.REPEAT -> return runLoop(step, loops[step.id].orEmpty())
                StepAction.READ -> {
                    val text = ask(step.id, "What does \"${step.label}\" show on screen?", Expects.TEXT)
                    remember(step, text)
                    log(EntryKind.VARIABLE, "${variableName(step)} = $text", step.id)
                    return
                }
                StepAction.CLICK -> return log(EntryKind.PRESS, "Pressed ${step.label}", step.id)
                StepAction.NEXT_SCREEN, StepAction.OPEN_APP -> return
                StepAction.FILL, StepAction.TOGGLE -> Unit
            }
            if (sensitive(step)) {
                log(EntryKind.MANUAL, "${step.label}: typed by the user (never by voice)", step.id)
                return
            }
            step.valueExpression?.takeIf { it.isNotBlank() }?.let { expr ->
                val value = safe("") { Expressions.evaluateText(expr, lookup, today) }
                if (value.isNotEmpty()) {
                    if (step.action == StepAction.TOGGLE) setToggle(step, Expressions.truthy(value)) else fill(step, value, "computed")
                    return
                }
            }
            if (step.skip) {
                val default = step.defaultValue?.trim().orEmpty()
                if (default.isNotEmpty() && step.action == StepAction.FILL) fill(step, default, "default")
                else log(EntryKind.SKIP, "Skipped ${step.label}", step.id)
                return
            }
            val base = Expressions.template(step.question?.trim()?.takeIf { it.isNotEmpty() } ?: defaultQuestion(step), lookup, today)
            if (step.action == StepAction.TOGGLE) {
                for (i in 0 until MAX_ATTEMPTS) {
                    val a = ask(step.id, base, Expects.YESNO)
                    if (isYes(a)) return setToggle(step, true)
                    if (isNo(a)) return setToggle(step, false)
                    if (isSkip(a)) break
                    log(EntryKind.ERROR, "Sorry, I didn't catch that.", step.id)
                }
                log(EntryKind.SKIP, "Skipped ${step.label}", step.id)
                return
            }
            val profileVar = step.profileKey?.let { PROFILE_VARIABLES[it] }
            var suggestion: String? = step.defaultValue?.trim()?.takeIf { it.isNotEmpty() }
                ?: profileVar?.let { lookup(it) }?.let { Expressions.text(it).trim() }?.takeIf { it.isNotEmpty() }
            var attempt = 0
            while (attempt < MAX_ATTEMPTS) {
                val question = suggestion?.let { "$base Say yes to use $it." } ?: base
                val a = ask(step.id, question, Expects.TEXT)
                if (isSkip(a)) {
                    log(EntryKind.SKIP, "Skipped ${step.label}", step.id)
                    return
                }
                val s = suggestion
                if (s != null && isYes(a)) return fill(step, s, "fill")
                if (s != null && isNo(a)) {
                    // Declining the suggestion doesn't use up an attempt.
                    suggestion = null
                    continue
                }
                val check = FieldValidator.validate(a, step.fieldType, step.rules)
                if (check is FieldValidator.Result.Invalid) {
                    log(EntryKind.ERROR, "${check.message}. Please try again.", step.id)
                    attempt++
                    continue
                }
                return fill(step, a.trim(), "fill")
            }
            log(EntryKind.SKIP, "Skipped ${step.label} after $MAX_ATTEMPTS tries", step.id)
        }

        fun runLoop(loop: FlowStep, body: List<FlowStep>) {
            val spec = loop.repeat ?: return
            if (body.isEmpty()) return
            val item = spec.itemLabel?.trim()?.takeIf { it.isNotEmpty() } ?: loop.label.ifEmpty { "item" }
            val max = spec.maxIterations.coerceIn(1, 50)
            try {
                for (index in 1..max) {
                    vars["index"] = index.toString()
                    log(EntryKind.SCREEN, "$item $index", loop.id)
                    runSteps(body, emptyMap())
                    val count = spec.countExpression?.takeIf { it.isNotBlank() }
                    val more = if (count != null) {
                        val n = safe<Any?>(null) { Expressions.evaluate(count, lookup, today) }
                        index < (Expressions.number(n) ?: 0.0).toLong()
                    } else {
                        isYes(ask(loop.id, "Add another $item?", Expects.YESNO))
                    }
                    if (!more || index == max) break
                    val addMore = spec.addMoreLabel?.trim()?.takeIf { it.isNotEmpty() }
                    if (addMore != null || spec.addMoreElementId != null) log(EntryKind.PRESS, "Pressed ${addMore ?: "add more"}", loop.id)
                }
            } finally {
                vars.remove("index")
            }
        }

        fun runSteps(list: List<FlowStep>, loops: Map<String, List<FlowStep>>) {
            list.forEach { step -> if (!conditionHolds(step)) applyElse(step) else runStep(step, loops) }
        }

        fun run(ordered: List<FlowStep>): Result = try {
            segments(ordered).forEachIndexed { i, segment ->
                val first = segment.firstOrNull()
                when {
                    first?.action == StepAction.OPEN_APP -> log(EntryKind.SCREEN, "Open app ${first.appPackage?.ifEmpty { null } ?: "?"}", first.id)
                    first?.action == StepAction.NEXT_SCREEN ->
                        log(EntryKind.SCREEN, "Wait for the next screen${first.appPackage?.takeIf { it.isNotEmpty() }?.let { " in $it" }.orEmpty()}", first.id)
                    i == 0 -> log(EntryKind.SCREEN, "Start")
                }
                val repeats = segment.filter { it.action == StepAction.REPEAT }
                val bodyIds = repeats.flatMap { it.repeat?.stepIds.orEmpty() }.toSet()
                val loops = repeats.associate { r ->
                    r.id to segment.filter { b -> r.repeat?.stepIds?.contains(b.id) == true && b.action != StepAction.REPEAT }
                }
                val submit = segment.lastOrNull { it.action == StepAction.CLICK && it.condition.isNullOrBlank() && it.id !in bodyIds }
                runSteps(segment.filter { it.id !in bodyIds && it !== submit }, loops)
                if (submit != null) {
                    if (submit.skip) {
                        log(EntryKind.PRESS, "Pressed ${submit.label}", submit.id)
                    } else {
                        val q = Expressions.template(
                            submit.question?.trim()?.takeIf { it.isNotEmpty() } ?: "All done. Shall I press ${submit.label}?", lookup, today,
                        )
                        val a = ask(submit.id, q, Expects.YESNO)
                        if (isYes(a) || a.trim().lowercase() in setOf("submit", "next")) log(EntryKind.PRESS, "Pressed ${submit.label}", submit.id)
                        else log(EntryKind.SKIP, "Did not press ${submit.label}", submit.id)
                    }
                }
            }
            log(EntryKind.DONE, "All done.")
            Result(transcript.toList(), vars.toMap(), values.toMap(), null, true)
        } catch (e: NeedsInput) {
            Result(transcript.toList(), vars.toMap(), values.toMap(), e.pending, false)
        }
    }
}
