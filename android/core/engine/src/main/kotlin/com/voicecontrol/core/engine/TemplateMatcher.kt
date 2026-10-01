package com.voicecontrol.core.engine

import com.voicecontrol.core.model.ElementKind
import com.voicecontrol.core.model.FieldType
import com.voicecontrol.core.model.FlowDefinition
import com.voicecontrol.core.model.FlowStep
import com.voicecontrol.core.model.ScreenElement
import com.voicecontrol.core.model.ScreenSnapshot
import com.voicecontrol.core.screen.LabelText

/** A starter template (sign-up, login, address…) with the words that identify each step's field. */
data class FlowTemplate(
    val id: String,
    val name: String,
    val category: String,
    val version: Int,
    val steps: List<FlowStep>,
    val keywords: Map<String, List<String>>,
)

/**
 * Fits starter templates to screens that have no saved flow, so even a first visit gets good
 * questions, validation rules and profile suggestions. Template steps are matched to on-screen
 * elements by keywords in the label and by field type. Same algorithm as the dashboard's
 * "apply template"; shared vectors in `docs/spec/template-matching.json`.
 */
object TemplateMatcher {

    /** Template step id → matched element. */
    fun match(template: FlowTemplate, elements: List<ScreenElement>): Map<String, ScreenElement> {
        val candidates = mutableListOf<Triple<FlowStep, ScreenElement, Int>>()
        template.steps.forEach { step ->
            elements.forEach { element ->
                val score = score(step, template.keywords[step.id].orEmpty(), element)
                if (score > 0) candidates += Triple(step, element, score)
            }
        }
        val result = LinkedHashMap<String, ScreenElement>()
        val used = mutableSetOf<String>()
        // Best pairs first, so "Confirm password" doesn't steal the "Password" step.
        candidates.sortedWith(compareByDescending<Triple<FlowStep, ScreenElement, Int>> { it.third }.thenBy { it.first.order })
            .forEach { (step, element, _) ->
                if (step.id in result || element.id in used) return@forEach
                result[step.id] = element
                used += element.id
            }
        return result
    }

    /** The best-fitting template adapted to this screen, or null when none fits well. */
    fun best(snapshot: ScreenSnapshot, templates: List<FlowTemplate>): FlowDefinition? {
        val elements = snapshot.elements
        return templates.mapNotNull { t ->
            val matched = match(t, elements)
            val fields = t.steps.filter { !it.kind.isButton }
            val matchedFields = fields.count { it.id in matched }
            if (matchedFields < MIN_FIELDS || matchedFields * 2 < fields.size) null else Triple(t, matched, matchedFields)
        }.maxWithOrNull(compareBy<Triple<FlowTemplate, Map<String, ScreenElement>, Int>> { it.third }.thenBy { it.third.toDouble() / it.first.steps.size })
            ?.let { (template, matched, _) -> adapt(template, matched, snapshot) }
    }

    private fun adapt(template: FlowTemplate, matched: Map<String, ScreenElement>, snapshot: ScreenSnapshot): FlowDefinition {
        val steps = template.steps.filter { it.id in matched }.mapIndexed { index, step ->
            val element = matched.getValue(step.id)
            step.copy(
                order = index,
                elementId = element.id,
                label = element.label,
                kind = element.kind,
                fieldType = element.fieldType ?: step.fieldType,
                action = when {
                    element.kind.isToggle -> com.voicecontrol.core.model.StepAction.TOGGLE
                    element.kind == ElementKind.BUTTON || element.kind == ElementKind.LINK -> com.voicecontrol.core.model.StepAction.CLICK
                    else -> step.action
                },
                defaultValue = null,
            )
        }
        return FlowDefinition(
            id = TEMPLATE_PREFIX + template.id,
            version = template.version,
            appPackage = snapshot.packageName,
            name = template.name,
            screenSignature = snapshot.signature,
            steps = steps,
        )
    }

    /** 0 = no match. Keyword in label: 2 (+1 exact label, +1 type agrees). Type alone counts only for distinctive types. */
    private fun score(step: FlowStep, keywords: List<String>, element: ScreenElement): Int {
        if (!compatible(step.kind, element.kind)) return 0
        val label = LabelText.normalize(element.label + " " + (element.hint ?: ""))
        val words = keywords.map(LabelText::normalize).filter { it.isNotEmpty() }
        val hit = words.filter { containsWords(label, it) }.maxByOrNull { it.length }
        val typeAgrees = step.fieldType != null && element.fieldType == step.fieldType
        return when {
            hit != null -> 2 + (if (LabelText.normalize(element.label) == hit) 1 else 0) + (if (typeAgrees) 1 else 0) + hit.length / 8
            typeAgrees && step.fieldType in DISTINCTIVE -> 1
            else -> 0
        }
    }

    private fun containsWords(label: String, keyword: String): Boolean =
        Regex("(^|\\s)" + Regex.escape(keyword) + "(\\s|$)").containsMatchIn(label)

    private fun compatible(stepKind: ElementKind, elementKind: ElementKind): Boolean = when {
        stepKind.isButton -> elementKind.isButton
        stepKind.isToggle -> elementKind.isToggle
        else -> elementKind.isInput
    }

    private val ElementKind.isButton: Boolean get() = this == ElementKind.BUTTON || this == ElementKind.LINK

    private val DISTINCTIVE = setOf(FieldType.EMAIL, FieldType.PHONE, FieldType.PINCODE, FieldType.PASSWORD, FieldType.DATE)
    private const val MIN_FIELDS = 2
    const val TEMPLATE_PREFIX = "template:"
}
