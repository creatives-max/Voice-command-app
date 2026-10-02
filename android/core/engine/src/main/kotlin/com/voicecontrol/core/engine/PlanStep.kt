package com.voicecontrol.core.engine

import com.voicecontrol.core.model.ElementKind
import com.voicecontrol.core.model.FieldType
import com.voicecontrol.core.model.FlowStep
import com.voicecontrol.core.model.ProfileKey
import com.voicecontrol.core.model.RepeatSpec
import com.voicecontrol.core.model.ScreenElement
import com.voicecontrol.core.model.StepAction

/** A step the session will handle, merged from the live screen and (optionally) a saved flow. */
data class PlanStep(
    val element: ScreenElement,
    val action: StepAction,
    val question: String,
    val customQuestion: Boolean,
    /** Said when the user is stuck or the answer didn't fit (AI-written; see QuestionWriter). */
    val hint: String? = null,
    val rules: List<String> = emptyList(),
    /** Value to offer ("say yes to use …"): from the flow default or the user profile. */
    val suggestedValue: String? = null,
    val defaultValue: String? = null,
    val skip: Boolean = false,
    val helpVideoUrl: String? = null,
    val profileKey: ProfileKey? = null,
    val flowStepId: String? = null,
    /** Variable that receives this step's answer. */
    val variable: String? = null,
    /** Run only when this expression is true. */
    val condition: String? = null,
    /** Fill this expression's value when [condition] is false. */
    val elseValue: String? = null,
    /** Fill (or store) this expression's value without asking. */
    val valueExpression: String? = null,
    val repeat: RepeatSpec? = null,
    /** Flow steps run once per item by a REPEAT step. */
    val repeatBody: List<FlowStep> = emptyList(),
    /** Logic steps (SET_VARIABLE, REPEAT) have no element on screen. */
    val virtual: Boolean = false,
) {
    val elementId: String get() = element.id
    val label: String get() = element.label
    val fieldType: FieldType? get() = element.fieldType
    val kind: ElementKind get() = element.kind
    val isSensitive: Boolean get() = element.isSensitive
}

/** The plan for one screen: steps to run, plus the button that finishes the screen. */
data class ScreenPlan(
    val steps: List<PlanStep>,
    val submitButton: ScreenElement?,
    /** Custom confirmation question for the submit button (from the flow). */
    val submitQuestion: String?,
    /** When true the submit button is pressed without asking (flow marked the click step as skip). */
    val autoSubmit: Boolean,
    val flowId: String?,
    val flowVersion: Int?,
    /** The submit button is the flow's own click step (not a guess from the screen). */
    val submitFromFlow: Boolean = false,
)
