package com.voicecontrol.feature.history

import com.voicecontrol.core.model.RunStatus
import com.voicecontrol.core.model.SessionSummary
import com.voicecontrol.core.model.StepOutcome

data class HistoryState(
    val loading: Boolean = true,
    val sessions: List<SessionSummary> = emptyList(),
    val selected: SessionSummary? = null,
    val historyEnabled: Boolean = true,
) {
    val totals: HistoryTotals get() = HistoryTotals.of(sessions)
}

data class HistoryTotals(val sessions: Int, val completed: Int, val fieldsFilled: Int) {
    companion object {
        fun of(list: List<SessionSummary>) = HistoryTotals(
            sessions = list.size,
            completed = list.count { it.status == RunStatus.COMPLETED },
            fieldsFilled = list.sumOf { it.filledCount },
        )
    }
}

sealed interface HistoryIntent {
    data class Open(val sessionId: String) : HistoryIntent
    data object CloseDetail : HistoryIntent
    data class Delete(val sessionId: String) : HistoryIntent
    data object ClearAll : HistoryIntent
}

sealed interface HistoryEffect {
    data class Message(val text: String) : HistoryEffect
}

fun StepOutcome.label(): String = when (this) {
    StepOutcome.FILLED -> "Filled by voice"
    StepOutcome.DEFAULT_FILLED -> "Filled with default"
    StepOutcome.KEPT -> "Kept existing value"
    StepOutcome.SKIPPED -> "Skipped"
    StepOutcome.MANUAL -> "Typed by you"
    StepOutcome.CLICKED -> "Pressed"
    StepOutcome.TOGGLED -> "Toggled"
    StepOutcome.FAILED -> "Failed"
}

fun formatDuration(millis: Long): String {
    val seconds = (millis / 1000).coerceAtLeast(0)
    return if (seconds < 60) "${seconds}s" else "${seconds / 60}m ${seconds % 60}s"
}
