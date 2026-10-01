package com.voicecontrol.feature.history

import com.voicecontrol.core.model.ElementKind
import com.voicecontrol.core.model.Language
import com.voicecontrol.core.model.RunStatus
import com.voicecontrol.core.model.ScreenRecord
import com.voicecontrol.core.model.SessionSummary
import com.voicecontrol.core.model.StepOutcome
import com.voicecontrol.core.model.StepRecord
import kotlin.test.Test
import kotlin.test.assertEquals

class HistoryContractTest {
    private fun session(status: RunStatus, filled: Int) = SessionSummary(
        "id-$status-$filled", "com.app", 0, 65_000, status, Language.ENGLISH,
        listOf(ScreenRecord("com.app", screenSignature = "s", steps = List(filled) { StepRecord("e$it", "L", ElementKind.TEXT_FIELD, outcome = StepOutcome.FILLED) })),
    )

    @Test
    fun `totals and formatting`() {
        val totals = HistoryTotals.of(listOf(session(RunStatus.COMPLETED, 2), session(RunStatus.STOPPED, 1)))
        assertEquals(HistoryTotals(2, 1, 3), totals)
        assertEquals("1m 5s", formatDuration(65_000))
        assertEquals("9s", formatDuration(9_400))
        assertEquals("Typed by you", StepOutcome.MANUAL.label())
    }
}
