package com.voicecontrol.application.automation

import com.voicecontrol.domain.common.DomainException
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CronScheduleTest {
    private val kolkata = ZoneId.of("Asia/Kolkata")
    private val utc = ZoneId.of("UTC")
    private fun at(s: String) = Instant.parse(s)

    @Test
    fun `daily time in a time zone`() {
        val s = CronSchedule.parse("30 9 * * *")
        // 2026-10-01T03:00Z is 08:30 in Kolkata -> next 09:30 IST = 04:00Z
        assertEquals(at("2026-10-01T04:00:00Z"), s.next(at("2026-10-01T03:00:00Z"), kolkata))
        // strictly after: exactly at fire time goes to the next day
        assertEquals(at("2026-10-02T04:00:00Z"), s.next(at("2026-10-01T04:00:00Z"), kolkata))
    }

    @Test
    fun `weekdays, steps, lists and names`() {
        // 2026-10-03 is a Saturday
        assertEquals(at("2026-10-05T08:00:00Z"), CronSchedule.parse("0 8 * * MON-FRI").next(at("2026-10-02T09:00:00Z"), utc))
        assertEquals(at("2026-10-01T10:15:00Z"), CronSchedule.parse("*/15 * * * *").next(at("2026-10-01T10:01:00Z"), utc))
        assertEquals(
            listOf(at("2026-10-01T09:00:00Z"), at("2026-10-01T13:00:00Z"), at("2026-10-02T09:00:00Z")),
            CronSchedule.parse("0 9,13 * * *").upcoming(at("2026-10-01T08:00:00Z"), utc, 3),
        )
        assertEquals(at("2027-01-01T00:00:00Z"), CronSchedule.parse("0 0 1 JAN *").next(at("2026-10-01T00:00:00Z"), utc))
        assertEquals(at("2026-10-04T07:00:00Z"), CronSchedule.parse("0 7 * * 7").next(at("2026-10-01T00:00:00Z"), utc))
    }

    @Test
    fun `day of month or day of week when both are set`() {
        // 15th of the month OR Mondays: from Thu 2026-10-01 the first match is Mon 2026-10-05
        assertEquals(at("2026-10-05T06:00:00Z"), CronSchedule.parse("0 6 15 * 1").next(at("2026-10-01T00:00:00Z"), utc))
    }

    @Test
    fun `daylight saving keeps the wall clock time`() {
        val ny = ZoneId.of("America/New_York")
        val s = CronSchedule.parse("0 9 * * *")
        assertEquals(at("2026-10-31T13:00:00Z"), s.next(at("2026-10-31T12:00:00Z"), ny)) // EDT
        assertEquals(at("2026-11-02T14:00:00Z"), s.next(at("2026-11-01T14:30:00Z"), ny)) // EST
    }

    @Test
    fun `invalid schedules are rejected and impossible ones never run`() {
        listOf("", "* * * *", "60 * * * *", "* 24 * * *", "* * 0 * *", "5-1 * * * *", "*/0 * * * *", "* * * FOO *").forEach {
            assertFailsWith<DomainException.Validation>(it) { CronSchedule.parse(it) }
        }
        assertNull(CronSchedule.parse("0 0 30 2 *").next(at("2026-01-01T00:00:00Z"), utc))
    }
}
