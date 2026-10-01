package com.voicecontrol.core.model

import kotlinx.serialization.Serializable

/**
 * What a flow step does.
 *
 * Element steps: [FILL], [CLICK], [TOGGLE], [READ] (store on-screen text in a variable).
 * Logic steps (no element): [SET_VARIABLE], [REPEAT] (loop over a group of steps for lists),
 * [NEXT_SCREEN] (wait for the app to show the next screen) and [OPEN_APP] (switch to another app).
 */
@Serializable
enum class StepAction {
    FILL, CLICK, TOGGLE, READ, SET_VARIABLE, REPEAT, NEXT_SCREEN, OPEN_APP;

    /** True for steps that operate an on-screen element. */
    val targetsElement: Boolean get() = this == FILL || this == CLICK || this == TOGGLE || this == READ

    /** True for steps that start a new screen segment of the flow. */
    val isScreenBoundary: Boolean get() = this == NEXT_SCREEN || this == OPEN_APP
}

/**
 * A loop over a group of steps, for lists ("add another item?").
 *
 * The steps in [stepIds] are run once per item. After each item, either [countExpression] decides
 * how many items there are, or the user is asked whether to add another. Before the next item the
 * "add more" button ([addMoreElementId], resolved by id then [addMoreLabel]) is pressed.
 * Inside the loop the variable `index` is the 1-based item number.
 */
@Serializable
data class RepeatSpec(
    val stepIds: List<String> = emptyList(),
    val addMoreElementId: String? = null,
    val addMoreLabel: String? = null,
    val maxIterations: Int = 10,
    val countExpression: String? = null,
    val itemLabel: String? = null,
)

/** Profile values that can pre-fill common fields. */
@Serializable
enum class ProfileKey { FULL_NAME, FIRST_NAME, LAST_NAME, EMAIL, PHONE, ADDRESS_LINE, CITY, STATE, PINCODE, DATE_OF_BIRTH }

/**
 * One step of a saved flow. Everything except [elementId]/[label]/[kind] can be edited in the dashboard.
 */
@Serializable
data class FlowStep(
    val id: String,
    val order: Int,
    val elementId: String,
    val label: String,
    val kind: ElementKind,
    val fieldType: FieldType? = null,
    val action: StepAction = StepAction.FILL,
    /** Custom question; null means "generate one from the label". */
    val question: String? = null,
    /** Validation rules, see `FieldValidator` for the syntax. */
    val rules: List<String> = emptyList(),
    val defaultValue: String? = null,
    /** Skipped steps are not asked; a default value (if any) is filled silently. */
    val skip: Boolean = false,
    val helpVideoUrl: String? = null,
    val profileKey: ProfileKey? = null,
    /** Expression; the step only runs when it is true (see `docs/spec/expressions.json`). */
    val condition: String? = null,
    /** Expression filled instead when [condition] is false ("else"). */
    val elseValue: String? = null,
    /** Variable that receives the step's answer; defaults to [FlowVariables.slug] of the label. */
    val variable: String? = null,
    /** Expression whose value is filled (or stored, for SET_VARIABLE) without asking. */
    val valueExpression: String? = null,
    val repeat: RepeatSpec? = null,
    /** OPEN_APP: the app to open. NEXT_SCREEN: optionally the app the next screen belongs to. */
    val appPackage: String? = null,
    /** NEXT_SCREEN / OPEN_APP: how long to wait for the screen to appear. */
    val waitSeconds: Int? = null,
)

/** Naming rules for flow variables, shared with the backend and dashboard. */
object FlowVariables {
    private val NAME = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

    fun isValidName(name: String): Boolean = NAME.matches(name)

    /** Default variable name for a step: its label in snake case, or `field_<order>` if that is empty. */
    fun slug(label: String, order: Int): String {
        val s = label.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
        return when {
            s.isEmpty() -> "field_$order"
            s.first().isDigit() -> "f_$s"
            else -> s
        }
    }

    fun nameOf(step: FlowStep): String = step.variable?.takeIf { it.isNotBlank() } ?: slug(step.label, step.order)

    /** Profile values are available to expressions as `profile.<name>`. */
    val profileNames: Map<ProfileKey, String> = mapOf(
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

    /** Built-in variable set by REPEAT steps. */
    const val INDEX = "index"
}

/** A saved, versioned voice flow for one screen of one app. */
@Serializable
data class FlowDefinition(
    /** Server id (UUID) once synced; local flows use a "local-" prefix. */
    val id: String,
    val version: Int = 1,
    val appPackage: String,
    val name: String,
    val screenSignature: String,
    val steps: List<FlowStep>,
    val updatedAtMillis: Long = 0L,
) {
    val orderedSteps: List<FlowStep> get() = steps.sortedBy { it.order }

    /**
     * The flow split into screens: a new segment starts at every NEXT_SCREEN / OPEN_APP step
     * (that step is the segment's first element and describes how to get there).
     */
    val segments: List<List<FlowStep>>
        get() {
            val out = mutableListOf<MutableList<FlowStep>>(mutableListOf())
            orderedSteps.forEach { step ->
                if (step.action.isScreenBoundary && out.last().isNotEmpty()) out += mutableListOf<FlowStep>()
                out.last() += step
            }
            return out
        }
    val isSynced: Boolean get() = !id.startsWith(LOCAL_PREFIX)

    companion object {
        const val LOCAL_PREFIX = "local-"
        /** Local flows taught by demonstration ("teach by doing"); still local until synced. */
        const val TAUGHT_PREFIX = "local-taught-"
    }
}

/** Reusable personal details (stored with consent) used to answer common questions. */
@Serializable
data class UserProfile(
    val fullName: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val addressLine: String? = null,
    val city: String? = null,
    val state: String? = null,
    val pincode: String? = null,
    val dateOfBirth: String? = null,
) {
    fun value(key: ProfileKey): String? = when (key) {
        ProfileKey.FULL_NAME -> fullName
        ProfileKey.FIRST_NAME -> fullName?.trim()?.substringBefore(' ')
        ProfileKey.LAST_NAME -> fullName?.trim()?.takeIf { ' ' in it }?.substringAfterLast(' ')
        ProfileKey.EMAIL -> email
        ProfileKey.PHONE -> phone
        ProfileKey.ADDRESS_LINE -> addressLine
        ProfileKey.CITY -> city
        ProfileKey.STATE -> state
        ProfileKey.PINCODE -> pincode
        ProfileKey.DATE_OF_BIRTH -> dateOfBirth
    }?.takeIf { it.isNotBlank() }
}
