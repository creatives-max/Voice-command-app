package com.voicecontrol.core.engine

import com.voicecontrol.core.model.ElementKind
import com.voicecontrol.core.model.FlowDefinition
import com.voicecontrol.core.model.FlowStep
import com.voicecontrol.core.model.ScreenRecord
import com.voicecontrol.core.model.StepAction
import com.voicecontrol.core.model.StepOutcome
import com.voicecontrol.core.model.StepRecord

/**
 * Turns a recorded screen into a [FlowDefinition] draft so the run can be replayed and edited later.
 * Typed values are deliberately NOT saved as defaults (they may be personal); users add defaults
 * explicitly in the dashboard.
 */
object FlowGenerator {

    /**
     * @param template the starter template that handled this screen, if any: its questions, rules and
     * profile suggestions are kept in the saved flow.
     */
    fun fromScreen(record: ScreenRecord, id: String, nowMillis: Long, template: FlowTemplate? = null): FlowDefinition {
        val fillSteps = record.steps.filter { it.kind != ElementKind.BUTTON && it.kind != ElementKind.LINK }.distinctBy { it.elementId }
        val click = record.steps.lastOrNull { (it.kind == ElementKind.BUTTON || it.kind == ElementKind.LINK) && it.outcome == StepOutcome.CLICKED }
        val generated = fillSteps.mapIndexed { index, step -> toFlowStep(step, index) } +
            listOfNotNull(click?.let { toFlowStep(it, fillSteps.size) })
        val steps = if (template == null) generated else withTemplate(generated, template)
        return FlowDefinition(
            id = id,
            version = 1,
            appPackage = record.appPackage,
            name = flowName(record),
            screenSignature = record.screenSignature,
            steps = steps,
            updatedAtMillis = nowMillis,
        )
    }

    private fun toFlowStep(step: StepRecord, order: Int) = FlowStep(
        id = "step-$order-${step.elementId.hashCode().toUInt().toString(16)}",
        order = order,
        elementId = step.elementId,
        label = step.label,
        kind = step.kind,
        fieldType = step.fieldType,
        action = when {
            step.kind == ElementKind.BUTTON || step.kind == ElementKind.LINK -> StepAction.CLICK
            step.kind.isToggle -> StepAction.TOGGLE
            else -> StepAction.FILL
        },
        question = null,
        skip = false,
    )

    private fun withTemplate(steps: List<FlowStep>, template: FlowTemplate): List<FlowStep> {
        val elements = steps.map { com.voicecontrol.core.model.ScreenElement(it.elementId, it.kind, it.label, it.fieldType) }
        val byElement = TemplateMatcher.match(template, elements).entries.associate { (stepId, element) -> element.id to template.steps.first { it.id == stepId } }
        return steps.map { step ->
            val t = byElement[step.elementId] ?: return@map step
            step.copy(question = t.question, rules = t.rules, profileKey = t.profileKey)
        }
    }

    fun flowName(record: ScreenRecord): String {
        val title = record.screenTitle?.takeIf { it.isNotBlank() }
            ?: record.activityName?.substringAfterLast('.')?.removeSuffix("Activity")?.takeIf { it.isNotBlank() }
        val app = record.appPackage.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        return if (title != null) "$app · $title" else app
    }
}
