package com.voicecontrol.core.engine

import com.voicecontrol.core.model.ElementKind
import com.voicecontrol.core.model.FieldType
import com.voicecontrol.core.model.FlowDefinition
import com.voicecontrol.core.model.FlowStep
import com.voicecontrol.core.model.ProfileKey
import com.voicecontrol.core.model.ScreenElement
import com.voicecontrol.core.model.ScreenSnapshot
import com.voicecontrol.core.model.StepAction
import com.voicecontrol.core.model.UserProfile
import com.voicecontrol.core.screen.LabelText

/**
 * Builds the question plan for a screen.
 *
 * With a saved flow, its step order, questions, rules, defaults, skips and help videos win;
 * flow steps are matched to live elements by stable id, then by label. Fields on screen that the
 * flow doesn't know (e.g. the app added a field) are appended in reading order.
 */
class PlanBuilder(private val phrases: Phrases) {

    fun build(snapshot: ScreenSnapshot, flow: FlowDefinition?, profile: UserProfile?): ScreenPlan {
        val fields = snapshot.elements.filter { it.kind.isInput || it.kind.isToggle }
        val used = mutableSetOf<String>()
        val steps = mutableListOf<PlanStep>()
        var submitButton: ScreenElement? = null
        var submitQuestion: String? = null
        var autoSubmit = false

        flow?.orderedSteps?.forEach { fs ->
            val element = resolve(fs, snapshot.elements, used) ?: return@forEach
            used += element.id
            if (fs.action == StepAction.CLICK) {
                submitButton = element
                submitQuestion = fs.question
                autoSubmit = fs.skip
            } else {
                steps += fromFlowStep(fs, element, profile)
            }
        }
        fields.filter { it.id !in used }.forEach { element ->
            used += element.id
            steps += fromElement(element, profile)
        }
        if (submitButton == null) submitButton = ButtonMatcher.primarySubmit(snapshot.elements)
        return ScreenPlan(steps, submitButton, submitQuestion, autoSubmit, flow?.id, flow?.version)
    }

    private fun resolve(step: FlowStep, elements: List<ScreenElement>, used: Set<String>): ScreenElement? {
        elements.firstOrNull { it.id == step.elementId && it.id !in used }?.let { return it }
        val label = LabelText.normalize(step.label)
        return elements.firstOrNull { it.id !in used && it.kind == step.kind && LabelText.normalize(it.label) == label }
    }

    private fun fromFlowStep(fs: FlowStep, element: ScreenElement, profile: UserProfile?): PlanStep {
        val profileKey = fs.profileKey ?: inferProfileKey(element)
        val profileValue = profileKey?.let { profile?.value(it) }
        val base = fromElement(element, profile)
        return base.copy(
            action = if (element.kind.isToggle) StepAction.TOGGLE else fs.action,
            question = fs.question?.takeIf { it.isNotBlank() } ?: base.question,
            customQuestion = !fs.question.isNullOrBlank(),
            rules = fs.rules,
            defaultValue = fs.defaultValue?.takeIf { it.isNotBlank() },
            suggestedValue = fs.defaultValue?.takeIf { it.isNotBlank() } ?: profileValue,
            skip = fs.skip,
            helpVideoUrl = fs.helpVideoUrl?.takeIf { it.isNotBlank() },
            profileKey = profileKey,
            flowStepId = fs.id,
        )
    }

    private fun fromElement(element: ScreenElement, profile: UserProfile?): PlanStep {
        val profileKey = inferProfileKey(element)
        val question = when {
            element.kind.isToggle -> phrases.askToggle(element.label)
            element.kind == ElementKind.DROPDOWN -> phrases.askDropdown(element.label)
            else -> phrases.ask(element.label, element.fieldType)
        }
        return PlanStep(
            element = element,
            action = if (element.kind.isToggle) StepAction.TOGGLE else StepAction.FILL,
            question = question,
            customQuestion = false,
            suggestedValue = if (element.isSensitive) null else profileKey?.let { profile?.value(it) },
            profileKey = profileKey,
        )
    }

    companion object {
        /** Guess which profile value answers this field. Never for sensitive fields. */
        fun inferProfileKey(element: ScreenElement): ProfileKey? {
            if (element.isSensitive || element.kind != ElementKind.TEXT_FIELD) return null
            val label = LabelText.normalize(element.label + " " + (element.viewId ?: "").replace('_', ' '))
            return when {
                element.fieldType == FieldType.EMAIL -> ProfileKey.EMAIL
                element.fieldType == FieldType.PHONE -> ProfileKey.PHONE
                element.fieldType == FieldType.PINCODE -> ProfileKey.PINCODE
                element.fieldType == FieldType.DATE && ("birth" in label || "dob" in label || "जन्म" in label) -> ProfileKey.DATE_OF_BIRTH
                element.fieldType == FieldType.NAME && ("first" in label || "पहला" in label) -> ProfileKey.FIRST_NAME
                element.fieldType == FieldType.NAME && ("last" in label || "surname" in label || "उपनाम" in label) -> ProfileKey.LAST_NAME
                element.fieldType == FieldType.NAME && ("company" !in label && "father" !in label && "mother" !in label && "nominee" !in label) -> ProfileKey.FULL_NAME
                element.fieldType == FieldType.ADDRESS -> ProfileKey.ADDRESS_LINE
                "city" in label || "शहर" in label -> ProfileKey.CITY
                "state" in label || "राज्य" in label -> ProfileKey.STATE
                else -> null
            }
        }
    }
}
