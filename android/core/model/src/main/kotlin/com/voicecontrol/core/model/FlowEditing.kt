package com.voicecontrol.core.model

/**
 * Edits made to a flow on the phone (for on-device-only use, without the dashboard). Every function
 * returns a new flow with steps renumbered in list order; invalid edits throw [IllegalArgumentException].
 */
object FlowEditing {
    const val MAX_TEXT = 500

    fun rename(flow: FlowDefinition, name: String): FlowDefinition {
        val n = name.trim()
        require(n.isNotEmpty() && n.length <= 120) { "Name must be 1-120 characters" }
        return flow.copy(name = n)
    }

    fun setQuestion(flow: FlowDefinition, stepId: String, question: String?): FlowDefinition =
        update(flow, stepId) { s ->
            val q = question?.trim()?.takeIf { it.isNotEmpty() }
            require((q?.length ?: 0) <= MAX_TEXT) { "Question is too long" }
            s.copy(question = q)
        }

    /** Passwords, OTPs and PINs can never have a default; buttons don't take values. */
    fun setDefault(flow: FlowDefinition, stepId: String, value: String?): FlowDefinition =
        update(flow, stepId) { s ->
            val v = value?.trim()?.takeIf { it.isNotEmpty() }
            if (v != null) {
                require(s.fieldType?.isSensitive != true) { "Password, OTP and PIN fields can't have a default value" }
                require(s.action == StepAction.FILL) { "Only fields you fill can have a default value" }
                require(v.length <= MAX_TEXT) { "Default value is too long" }
            }
            s.copy(defaultValue = v)
        }

    fun setSkip(flow: FlowDefinition, stepId: String, skip: Boolean): FlowDefinition = update(flow, stepId) { it.copy(skip = skip) }

    /** Moves a step by [delta] places (clamped to the list). */
    fun move(flow: FlowDefinition, stepId: String, delta: Int): FlowDefinition {
        val steps = flow.orderedSteps.toMutableList()
        val from = steps.indexOfFirst { it.id == stepId }
        require(from >= 0) { "Unknown step" }
        val to = (from + delta).coerceIn(0, steps.lastIndex)
        if (to == from) return flow
        steps.add(to, steps.removeAt(from))
        return flow.copy(steps = renumber(steps))
    }

    /** Removes a step and drops it from loops that repeat it. */
    fun remove(flow: FlowDefinition, stepId: String): FlowDefinition {
        require(flow.steps.any { it.id == stepId }) { "Unknown step" }
        val steps = flow.orderedSteps.filter { it.id != stepId }.map { s ->
            val r = s.repeat
            if (r != null && stepId in r.stepIds) s.copy(repeat = r.copy(stepIds = r.stepIds - stepId)) else s
        }
        return flow.copy(steps = renumber(steps))
    }

    private fun update(flow: FlowDefinition, stepId: String, change: (FlowStep) -> FlowStep): FlowDefinition {
        require(flow.steps.any { it.id == stepId }) { "Unknown step" }
        return flow.copy(steps = renumber(flow.orderedSteps.map { if (it.id == stepId) change(it) else it }))
    }

    private fun renumber(steps: List<FlowStep>) = steps.mapIndexed { i, s -> s.copy(order = i) }
}
