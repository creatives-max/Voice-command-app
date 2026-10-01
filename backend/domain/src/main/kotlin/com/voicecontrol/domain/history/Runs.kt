package com.voicecontrol.domain.history

import com.voicecontrol.domain.ai.ElementKind
import com.voicecontrol.domain.ai.FieldType
import com.voicecontrol.domain.ai.Language
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

@Serializable
enum class StepOutcome { FILLED, DEFAULT_FILLED, KEPT, SKIPPED, MANUAL, CLICKED, TOGGLED, FAILED }

@Serializable
enum class RunStatus { COMPLETED, STOPPED, FAILED }

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

data class Run(
    val id: UUID,
    val userId: UUID,
    val appPackage: String,
    val startedAt: Instant,
    val endedAt: Instant,
    val status: RunStatus,
    val language: Language,
    val screens: List<ScreenRecord>,
) {
    val filledCount: Int get() = screens.sumOf { s -> s.steps.count { it.outcome == StepOutcome.FILLED || it.outcome == StepOutcome.DEFAULT_FILLED } }
    val stepCount: Int get() = screens.sumOf { it.steps.size }
}

data class RunStats(val totalRuns: Int, val completedRuns: Int, val fieldsFilled: Int, val topApps: List<Pair<String, Int>>)

interface RunRepository {
    /** Inserts runs, ignoring ids that already exist (uploads are retried). Returns the number inserted. */
    suspend fun insertAll(runs: List<Run>): Int
    suspend fun list(userId: UUID, appPackage: String?, limit: Int, offset: Int): List<Run>
    suspend fun get(userId: UUID, id: UUID): Run?
    suspend fun delete(userId: UUID, id: UUID): Boolean
    suspend fun deleteAll(userId: UUID): Int
    suspend fun stats(userId: UUID): RunStats
}
