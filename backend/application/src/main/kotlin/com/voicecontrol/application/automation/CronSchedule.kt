package com.voicecontrol.application.automation

import com.voicecontrol.domain.common.DomainException
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * A standard 5-field cron schedule: `minute hour day-of-month month day-of-week`.
 *
 * Fields accept `*`, numbers, ranges (`1-5`), steps (`0-59/15`, `9-17/2`, or a star with a step) and lists (`1,15`); months and
 * weekdays also accept names (`JAN`, `MON`). Day-of-week 0 and 7 are Sunday. When both day fields are
 * restricted, a day matches if either matches (classic cron behaviour). Times are evaluated in the
 * trigger's time zone, so "every day at 09:00" stays at 09:00 across daylight-saving changes.
 */
class CronSchedule private constructor(
    val expression: String,
    private val minutes: Set<Int>,
    private val hours: Set<Int>,
    private val daysOfMonth: Set<Int>,
    private val months: Set<Int>,
    private val daysOfWeek: Set<Int>,
    private val domRestricted: Boolean,
    private val dowRestricted: Boolean,
) {

    /** First fire time strictly after [after], or null if none within ~5 years (e.g. Feb 30). */
    fun next(after: Instant, zone: ZoneId): Instant? {
        var t = ZonedDateTime.ofInstant(after, zone).truncatedTo(ChronoUnit.MINUTES).plusMinutes(1)
        val limit = t.plusYears(5)
        while (t.isBefore(limit)) {
            if (t.monthValue !in months) {
                t = t.withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS).plusMonths(1)
                continue
            }
            if (!dayMatches(t)) {
                t = t.truncatedTo(ChronoUnit.DAYS).plusDays(1)
                continue
            }
            if (t.hour !in hours) {
                t = t.truncatedTo(ChronoUnit.HOURS).plusHours(1)
                continue
            }
            if (t.minute !in minutes) {
                t = t.plusMinutes(1)
                continue
            }
            return t.toInstant()
        }
        return null
    }

    /** The next [count] fire times after [after]. */
    fun upcoming(after: Instant, zone: ZoneId, count: Int): List<Instant> {
        val out = mutableListOf<Instant>()
        var from = after
        repeat(count) {
            val n = next(from, zone) ?: return out
            out += n
            from = n
        }
        return out
    }

    private fun dayMatches(t: ZonedDateTime): Boolean {
        val dom = t.dayOfMonth in daysOfMonth
        val dow = (t.dayOfWeek.value % 7) in daysOfWeek
        return when {
            domRestricted && dowRestricted -> dom || dow
            domRestricted -> dom
            dowRestricted -> dow
            else -> true
        }
    }

    companion object {
        private val monthNames = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")
        private val dayNames = DayOfWeek.entries.map { it.name.take(3) } // MON..SUN

        fun parse(expression: String): CronSchedule {
            val fields = expression.trim().split(Regex("\\s+"))
            if (fields.size != 5) throw DomainException.Validation("A schedule needs 5 fields: minute hour day month weekday")
            val minutes = field(fields[0], 0, 59, "minute")
            val hours = field(fields[1], 0, 23, "hour")
            val dom = field(fields[2], 1, 31, "day of month")
            val months = field(fields[3], 1, 12, "month") { name -> monthNames.indexOf(name).takeIf { it >= 0 }?.plus(1) }
            val dow = field(fields[4], 0, 7, "weekday") { name -> dayNames.indexOf(name).takeIf { it >= 0 }?.let { (it + 1) % 7 } }
                .map { it % 7 }.toSet()
            return CronSchedule(expression.trim(), minutes, hours, dom, months, dow, fields[2] != "*", fields[4] != "*")
        }

        fun isValid(expression: String): Boolean = runCatching { parse(expression) }.isSuccess

        private fun field(text: String, min: Int, max: Int, what: String, names: (String) -> Int? = { null }): Set<Int> {
            fun value(s: String): Int {
                val v = s.toIntOrNull() ?: names(s.uppercase()) ?: throw DomainException.Validation("Invalid $what '$s'")
                if (v !in min..max) throw DomainException.Validation("The $what must be $min-$max")
                return v
            }
            val out = sortedSetOf<Int>()
            for (part in text.split(',')) {
                if (part.isEmpty()) throw DomainException.Validation("Invalid $what '$text'")
                val (range, stepText) = part.split('/', limit = 2).let { it[0] to it.getOrNull(1) }
                val step = stepText?.let { it.toIntOrNull()?.takeIf { s -> s > 0 } ?: throw DomainException.Validation("Invalid step in $what") } ?: 1
                val (from, to) = when {
                    range == "*" -> min to max
                    '-' in range -> range.split('-', limit = 2).let { value(it[0]) to value(it[1]) }
                    else -> value(range).let { it to if (stepText != null) max else it }
                }
                if (from > to) throw DomainException.Validation("Invalid range in $what")
                for (v in from..to step step) out += v
            }
            return out
        }
    }
}
