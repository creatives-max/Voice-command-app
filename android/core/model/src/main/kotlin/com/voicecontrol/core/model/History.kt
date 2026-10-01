package com.voicecontrol.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class StepOutcome { FILLED, DEFAULT_FILLED, KEPT, SKIPPED, MANUAL, CLICKED, TOGGLED, FAILED }

@Serializable
enum class RunStatus { COMPLETED, STOPPED, FAILED }

/** What happened to one element during a session. Values of sensitive fields are never recorded. */
@Serializable
data class StepRecord(
    val elementId: String,
    val label: String,
    val kind: ElementKind,
    val fieldType: FieldType? = null,
    val question: String? = null,
    val outcome: StepOutcome,
    val interpretedBy: String? = null,
)

/** One screen handled during a session. */
@Serializable
data class ScreenRecord(
    val appPackage: String,
    val activityName: String? = null,
    val screenTitle: String? = null,
    val screenSignature: String,
    val flowId: String? = null,
    val flowVersion: Int? = null,
    val steps: List<StepRecord>,
)

/** A finished voice session, stored as history and used to create/refresh flows. */
@Serializable
data class SessionSummary(
    val sessionId: String,
    val appPackage: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val status: RunStatus,
    val language: Language,
    val screens: List<ScreenRecord>,
) {
    val filledCount: Int get() = screens.sumOf { s -> s.steps.count { it.outcome == StepOutcome.FILLED || it.outcome == StepOutcome.DEFAULT_FILLED } }
    val durationMillis: Long get() = endedAtMillis - startedAtMillis
}
