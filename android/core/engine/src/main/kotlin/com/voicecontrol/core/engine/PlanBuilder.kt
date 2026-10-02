package com.voicecontrol.core.engine

import com.voicecontrol.core.model.ElementKind
import com.voicecontrol.core.model.FieldType
import com.voicecontrol.core.model.FlowDefinition
import com.voicecontrol.core.model.FlowStep
import com.voicecontrol.core.model.FlowVariables
import com.voicecontrol.core.model.ProfileKey
import com.voicecontrol.core.model.ScreenElement
import com.voicecontrol.core.model.ScreenSnapshot
import com.voicecontrol.core.model.StepAction
import com.voicecontrol.core.model.UserProfile
import com.voicecontrol.core.screen.LabelText

/**
 * Builds the plan for a screen.
 *
 * With a saved flow (or one screen segment of a multi-screen flow), its step order, questions,
 * rules, defaults, skips, conditions, variables and help videos win; flow steps are matched to live
 * elements by stable id, then by label. Logic steps (SET_VARIABLE, REPEAT) become virtual steps,
 * the steps inside a REPEAT are resolved per item at run time, and unconditional CLICK steps pick
 * the submit button. Fields on screen that the flow doesn't know (e.g. the app added a field) are
 * appended in reading order.
 */
class PlanBuilder(private val phrases: Phrases) {

    fun build(snapshot: ScreenSnapshot, flow: FlowDefinition?, profile: UserProfile?): ScreenPlan {
        val fields = snapshot.elements.filter { it.kind.isInput || it.kind.isToggle }
        val used = mutableSetOf<String>()
        val steps = mutableListOf<PlanStep>()
        var submitButton: ScreenElement? = null
        var submitQuestion: String? = null
        var autoSubmit = false

        val ordered = flow?.orderedSteps.orEmpty()
        val byId = ordered.associateBy { it.id }
        val bodyIds = ordered.filter { it.action == StepAction.REPEAT }.flatMap { it.repeat?.stepIds.orEmpty() }.toSet()
        // Every element a repeated step could mean belongs to the loop, not to the plain plan.
        ordered.filter { it.id in bodyIds }.forEach { fs -> candidates(fs, snapshot.elements).forEach { used += it.id } }

        // Unconditional clicks before the screen's last one are pressed in order (a taught flow taps
        // "Search" and then works on what opens); the last one finishes the screen.
        val lastClick = ordered.lastOrNull { it.id !in bodyIds && it.action == StepAction.CLICK && it.condition.isNullOrBlank() }?.id
        ordered.forEach { fs ->
            if (fs.id in bodyIds) return@forEach
            when (fs.action) {
                StepAction.NEXT_SCREEN, StepAction.OPEN_APP -> return@forEach
                StepAction.SET_VARIABLE -> {
                    steps += virtualStep(fs)
                    return@forEach
                }
                StepAction.REPEAT -> {
                    val body = fs.repeat?.stepIds.orEmpty().mapNotNull(byId::get).filter { it.action != StepAction.REPEAT }
                    if (body.isNotEmpty()) steps += virtualStep(fs).copy(repeatBody = body.sortedBy { it.order })
                    return@forEach
                }
                else -> Unit
            }
            val element = resolve(fs, snapshot.elements, used) ?: return@forEach
            used += element.id
            if (fs.action == StepAction.CLICK && fs.condition.isNullOrBlank() && fs.id == lastClick) {
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
        // "Add another" buttons of loops are never the submit button.
        ordered.mapNotNull { it.repeat }.forEach { spec -> locate(spec.addMoreElementId, spec.addMoreLabel, snapshot.elements)?.let { used += it.id } }
        val fromFlow = submitButton != null
        if (submitButton == null) submitButton = ButtonMatcher.primarySubmit(snapshot.elements.filter { it.id !in used })
        return ScreenPlan(steps, submitButton, submitQuestion, autoSubmit, flow?.id, flow?.version, submitFromFlow = fromFlow)
    }

    /**
     * Resolves the steps of a REPEAT for item [iteration] (1-based): each step takes the
     * [iteration]-th matching element in reading order, or the last one when the list reuses one row.
     */
    fun resolveRepeatItem(snapshot: ScreenSnapshot, body: List<FlowStep>, iteration: Int, profile: UserProfile?): List<PlanStep> =
        body.mapNotNull { fs ->
            if (fs.action == StepAction.SET_VARIABLE) return@mapNotNull virtualStep(fs)
            if (!fs.action.targetsElement) return@mapNotNull null
            val matches = candidates(fs, snapshot.elements)
            // A reused row still shows the previous item's value; treat it as empty.
            val element = matches.getOrNull(iteration - 1) ?: matches.lastOrNull()?.copy(value = null, isChecked = null) ?: return@mapNotNull null
            fromFlowStep(fs, element, profile)
        }

    /** Finds an element by the flow step's id, then by label, among [elements]. */
    fun locate(elementId: String?, label: String?, elements: List<ScreenElement>): ScreenElement? {
        elementId?.let { id -> elements.firstOrNull { it.id == id }?.let { return it } }
        val wanted = label?.let(LabelText::normalize)?.takeIf { it.isNotEmpty() } ?: return null
        return elements.firstOrNull { LabelText.normalize(it.label) == wanted }
    }

    private fun candidates(step: FlowStep, elements: List<ScreenElement>): List<ScreenElement> {
        val label = LabelText.normalize(step.label)
        return elements.filter { it.id == step.elementId || (it.kind == step.kind && label.isNotEmpty() && LabelText.normalize(it.label) == label) }
    }

    private fun resolve(step: FlowStep, elements: List<ScreenElement>, used: Set<String>): ScreenElement? {
        elements.firstOrNull { it.id == step.elementId && it.id !in used }?.let { return it }
        val label = LabelText.normalize(step.label).takeIf { it.isNotEmpty() } ?: return null
        val free = elements.filter { it.id !in used }
        free.firstOrNull { it.kind == step.kind && LabelText.normalize(it.label) == label }?.let { return it }
        // A button may come back as a link, an icon or a tab: for presses the name is what counts.
        if (step.action == StepAction.CLICK) {
            free.firstOrNull { !it.kind.isInput && !it.kind.isToggle && LabelText.normalize(it.label) == label }?.let { return it }
        }
        return null
    }

    private fun virtualStep(fs: FlowStep): PlanStep = PlanStep(
        element = ScreenElement(id = "step:${fs.id}", kind = fs.kind, label = fs.label),
        action = fs.action,
        question = fs.question.orEmpty(),
        customQuestion = !fs.question.isNullOrBlank(),
        flowStepId = fs.id,
        variable = FlowVariables.nameOf(fs),
        condition = fs.condition?.takeIf { it.isNotBlank() },
        valueExpression = fs.valueExpression?.takeIf { it.isNotBlank() },
        repeat = fs.repeat,
        virtual = true,
    )

    private fun fromFlowStep(fs: FlowStep, element: ScreenElement, profile: UserProfile?): PlanStep {
        val profileKey = fs.profileKey ?: inferProfileKey(element)
        val profileValue = profileKey?.let { profile?.value(it) }
        val base = fromElement(element, profile)
        return base.copy(
            action = when {
                fs.action == StepAction.READ || fs.action == StepAction.CLICK -> fs.action
                element.kind.isToggle -> StepAction.TOGGLE
                else -> fs.action
            },
            question = fs.question?.takeIf { it.isNotBlank() } ?: base.question,
            customQuestion = !fs.question.isNullOrBlank(),
            rules = fs.rules,
            defaultValue = fs.defaultValue?.takeIf { it.isNotBlank() },
            suggestedValue = if (element.isSensitive) null else fs.defaultValue?.takeIf { it.isNotBlank() } ?: profileValue,
            skip = fs.skip,
            helpVideoUrl = fs.helpVideoUrl?.takeIf { it.isNotBlank() },
            profileKey = profileKey,
            flowStepId = fs.id,
            variable = FlowVariables.nameOf(fs),
            condition = fs.condition?.takeIf { it.isNotBlank() },
            elseValue = fs.elseValue?.takeIf { it.isNotBlank() },
            valueExpression = fs.valueExpression?.takeIf { it.isNotBlank() },
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
            variable = if (element.isSensitive) null else FlowVariables.slug(element.label, 0),
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
