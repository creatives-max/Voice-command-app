package com.voicecontrol.core.model

import kotlinx.serialization.Serializable

/** What a flow step does with its element. */
@Serializable
enum class StepAction { FILL, CLICK, TOGGLE }

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
)

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
    val isSynced: Boolean get() = !id.startsWith(LOCAL_PREFIX)

    companion object {
        const val LOCAL_PREFIX = "local-"
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
