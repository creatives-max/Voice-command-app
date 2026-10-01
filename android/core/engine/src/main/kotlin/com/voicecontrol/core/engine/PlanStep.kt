package com.voicecontrol.core.engine

import com.voicecontrol.core.model.ElementKind
import com.voicecontrol.core.model.FieldType
import com.voicecontrol.core.model.ProfileKey
import com.voicecontrol.core.model.ScreenElement
import com.voicecontrol.core.model.StepAction

/** A field the session will handle, merged from the live screen and (optionally) a saved flow. */
data class PlanStep(
    val element: ScreenElement,
    val action: StepAction,
    val question: String,
    val customQuestion: Boolean,
    val rules: List<String> = emptyList(),
    /** Value to offer ("say yes to use …"): from the flow default or the user profile. */
    val suggestedValue: String? = null,
    val defaultValue: String? = null,
    val skip: Boolean = false,
    val helpVideoUrl: String? = null,
    val profileKey: ProfileKey? = null,
    val flowStepId: String? = null,
) {
    val elementId: String get() = element.id
    val label: String get() = element.label
    val fieldType: FieldType? get() = element.fieldType
    val kind: ElementKind get() = element.kind
    val isSensitive: Boolean get() = element.isSensitive
}

/** The plan for one screen: fields to ask, plus the button that finishes the screen. */
data class ScreenPlan(
    val steps: List<PlanStep>,
    val submitButton: ScreenElement?,
    /** Custom confirmation question for the submit button (from the flow). */
    val submitQuestion: String?,
    /** When true the submit button is pressed without asking (flow marked the click step as skip). */
    val autoSubmit: Boolean,
    val flowId: String?,
    val flowVersion: Int?,
)
