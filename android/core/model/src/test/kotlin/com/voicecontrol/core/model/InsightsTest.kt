package com.voicecontrol.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InsightsTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    // 2026-10-01 10:00 in Kolkata.
    private val now = Instant.parse("2026-10-01T04:30:00Z")

    private fun session(id: String, daysAgo: Long, status: RunStatus, app: String = "com.bank", vararg outcomes: Pair<String, StepOutcome>): SessionSummary {
        val start = now.atZone(zone).minusDays(daysAgo).toInstant().toEpochMilli()
        return SessionSummary(
            id, app, start, start + 60_000, status, Language.HINDI,
            listOf(ScreenRecord(app, screenSignature = "sig", steps = outcomes.map { (label, o) -> StepRecord("vid:$label", label, ElementKind.TEXT_FIELD, outcome = o) })),
        )
    }

    @Test
    fun `summarises the period and compares with the one before`() {
        val sessions = listOf(
            session("a", 0, RunStatus.COMPLETED, "com.bank", "Name" to StepOutcome.FILLED, "PIN code" to StepOutcome.MANUAL),
            session("b", 0, RunStatus.COMPLETED, "com.bank", "Name" to StepOutcome.FILLED, "PIN code" to StepOutcome.SKIPPED),
            session("c", 3, RunStatus.STOPPED, "com.shop", "City" to StepOutcome.DEFAULT_FILLED),
            session("d", 6, RunStatus.FAILED, "com.bank"),
            session("old", 9, RunStatus.COMPLETED, "com.shop"),
            session("older", 20, RunStatus.COMPLETED, "com.shop"),
        )
        val i = Insights.of(sessions, 7, now, zone)
        assertEquals(4, i.runs)
        assertEquals(2, i.completed)
        assertEquals(0.5, i.successRate)
        assertEquals(3, i.filledByVoice)
        assertEquals(1, i.typedByHand)
        assertEquals(0.75, i.voiceShare)
        assertEquals(4 * 60_000L, i.totalMillis)
        assertEquals(7, i.daily.size)
        assertEquals(LocalDate.of(2026, 9, 25), i.daily.first().date)
        assertEquals(DayRuns(LocalDate.of(2026, 10, 1), 2, 0, 0), i.daily.last())
        assertEquals(LocalDate.of(2026, 10, 1), i.busiestDay?.date)
        assertEquals(listOf(AppInsight("com.bank", 3, 2), AppInsight("com.shop", 1, 0)), i.apps)
        // PIN code was typed or skipped both times; Name always worked; City was asked once.
        assertEquals(listOf(TroubleField("com.bank", "PIN code", 2, 2)), i.troubleFields)
        assertEquals(1, i.previousRuns)
        assertEquals(1.0, i.previousSuccessRate)
    }

    @Test
    fun `empty history and future-dated runs`() {
        val empty = Insights.of(emptyList(), 30, now, zone)
        assertEquals(0, empty.runs)
        assertNull(empty.successRate)
        assertNull(empty.voiceShare)
        assertNull(empty.busiestDay)
        assertEquals(30, empty.daily.size)

        val future = Insights.of(listOf(session("f", -2, RunStatus.COMPLETED)), 7, now, zone)
        assertEquals(1, future.runs)
        assertEquals(1, future.daily.last().completed)
    }
}

class ThemeModeTest {
    @Test
    fun `follows the phone unless chosen`() {
        assertEquals(true, ThemeMode.SYSTEM.isDark(systemDark = true))
        assertEquals(false, ThemeMode.SYSTEM.isDark(systemDark = false))
        assertEquals(true, ThemeMode.DARK.isDark(systemDark = false))
        assertEquals(false, ThemeMode.LIGHT.isDark(systemDark = true))
        assertEquals(ThemeMode.DARK, ThemeMode.parse("DARK"))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.parse("SEPIA"))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.parse(null))
    }
}
