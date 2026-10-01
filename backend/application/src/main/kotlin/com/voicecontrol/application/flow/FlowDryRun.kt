package com.voicecontrol.application.flow

import com.voicecontrol.domain.common.DomainException
import com.voicecontrol.domain.flow.FlowStep
import com.voicecontrol.domain.flow.ProfileKey
import com.voicecontrol.domain.user.Profile
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Dry run of a saved flow (or of unsaved edits to it) with scripted answers; see [FlowSimulator].
 * Nothing runs on a phone and nothing is stored.
 */
object FlowDryRun {
    const val MAX_ANSWERS = 500
    const val MAX_ANSWER_LENGTH = 500

    /** Profile values by expression name; blank values are left out. */
    fun profileVariables(p: Profile?): Map<String, String> = if (p == null) emptyMap() else FlowSimulator.profileVariables { key ->
        when (key) {
            ProfileKey.FULL_NAME -> p.fullName
            ProfileKey.FIRST_NAME -> p.fullName?.trim()?.substringBefore(' ')
            ProfileKey.LAST_NAME -> p.fullName?.trim()?.takeIf { ' ' in it }?.substringAfterLast(' ')
            ProfileKey.EMAIL -> p.email
            ProfileKey.PHONE -> p.phone
            ProfileKey.ADDRESS_LINE -> p.addressLine
            ProfileKey.CITY -> p.city
            ProfileKey.STATE -> p.state
            ProfileKey.PINCODE -> p.pincode
            ProfileKey.DATE_OF_BIRTH -> p.dateOfBirth
        }
    }

    /**
     * @param steps the flow's steps; unsaved edits are validated like a save.
     * @param today ISO date used by `today()`/`year()`; defaults to the current UTC date.
     */
    fun run(steps: List<FlowStep>, answers: List<String>, profile: Profile?, today: String? = null): FlowSimulator.Result {
        if (answers.size > MAX_ANSWERS) throw DomainException.Validation("At most $MAX_ANSWERS answers")
        if (answers.any { it.length > MAX_ANSWER_LENGTH }) throw DomainException.Validation("Answers can be at most $MAX_ANSWER_LENGTH characters")
        val date = today?.takeIf { it.isNotBlank() }?.let {
            runCatching { LocalDate.parse(it) }.getOrElse { throw DomainException.Validation("today must be a date like 2026-10-01") }
        } ?: LocalDate.now(ZoneOffset.UTC)
        return FlowSimulator.simulate(FlowService.normalizeSteps(steps), answers, profileVariables(profile)) { date }
    }
}
