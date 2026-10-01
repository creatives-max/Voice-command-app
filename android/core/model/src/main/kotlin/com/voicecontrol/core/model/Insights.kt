package com.voicecontrol.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Runs on one day of an insights period. */
data class DayRuns(val date: LocalDate, val completed: Int, val stopped: Int, val failed: Int) {
    val total: Int get() = completed + stopped + failed
}

/** How one app went in the period. */
data class AppInsight(val appPackage: String, val runs: Int, val completed: Int)

/** A field people often had to type by hand or skip: the question may need work. */
data class TroubleField(val appPackage: String, val label: String, val asked: Int, val trouble: Int) {
    val rate: Double get() = if (asked == 0) 0.0 else trouble.toDouble() / asked
}

/**
 * Analytics computed on the phone from its own history (nothing is uploaded for this): runs, success,
 * fields filled by voice, time spent, runs per day, busiest apps and the fields where voice struggled.
 */
data class Insights(
    val days: Int,
    val runs: Int,
    val completed: Int,
    val filledByVoice: Int,
    val typedByHand: Int,
    val totalMillis: Long,
    val daily: List<DayRuns>,
    val apps: List<AppInsight>,
    val troubleFields: List<TroubleField>,
    /** Runs in the same number of days just before the period. */
    val previousRuns: Int,
    val previousCompleted: Int,
) {
    /** completed / runs, or null without runs. */
    val successRate: Double? get() = if (runs == 0) null else completed.toDouble() / runs
    val previousSuccessRate: Double? get() = if (previousRuns == 0) null else previousCompleted.toDouble() / previousRuns
    /** Share of answered fields filled by voice rather than typed (0–1), or null when none. */
    val voiceShare: Double? get() = (filledByVoice + typedByHand).takeIf { it > 0 }?.let { filledByVoice.toDouble() / it }
    val busiestDay: DayRuns? get() = daily.filter { it.total > 0 }.maxByOrNull { it.total }

    companion object {
        val PERIODS = listOf(7, 30, 90)
        const val MAX_APPS = 5
        const val MAX_TROUBLE = 5
        /** A field needs this many answers before it is called out. */
        const val MIN_ASKED = 2

        private val VOICE = setOf(StepOutcome.FILLED, StepOutcome.DEFAULT_FILLED)
        private val TROUBLE = setOf(StepOutcome.MANUAL, StepOutcome.SKIPPED, StepOutcome.FAILED)

        fun of(sessions: List<SessionSummary>, days: Int, now: Instant, zone: ZoneId): Insights {
            val period = days.coerceIn(1, 365)
            val today = now.atZone(zone).toLocalDate()
            val first = today.minusDays((period - 1).toLong())
            val previousFirst = first.minusDays(period.toLong())
            fun dayOf(s: SessionSummary) = Instant.ofEpochMilli(s.startedAtMillis).atZone(zone).toLocalDate()

            // Runs dated after today (a clock change) still count as today.
            val current = sessions.filter { !dayOf(it).isBefore(first) }
            val previous = sessions.filter { val d = dayOf(it); !d.isBefore(previousFirst) && d.isBefore(first) }

            val byDay = current.groupBy { minOf(dayOf(it), today) }
            val daily = (0 until period).map { i ->
                val date = first.plusDays(i.toLong())
                val list = byDay[date].orEmpty()
                DayRuns(date, list.count { it.status == RunStatus.COMPLETED }, list.count { it.status == RunStatus.STOPPED }, list.count { it.status == RunStatus.FAILED })
            }

            val apps = current.groupBy { it.appPackage }
                .map { (app, list) -> AppInsight(app, list.size, list.count { it.status == RunStatus.COMPLETED }) }
                .sortedWith(compareByDescending<AppInsight> { it.runs }.thenBy { it.appPackage })
                .take(MAX_APPS)

            val fields = HashMap<Pair<String, String>, IntArray>()
            for (session in current) {
                for (screen in session.screens) {
                    for (step in screen.steps) {
                        if (step.kind != ElementKind.TEXT_FIELD) continue
                        val answered = step.outcome in VOICE || step.outcome in TROUBLE
                        if (!answered) continue
                        val counts = fields.getOrPut(screen.appPackage to step.label.ifBlank { step.elementId }) { IntArray(2) }
                        counts[0]++
                        if (step.outcome in TROUBLE) counts[1]++
                    }
                }
            }
            val trouble = fields.map { (key, c) -> TroubleField(key.first, key.second, c[0], c[1]) }
                .filter { it.asked >= MIN_ASKED && it.trouble > 0 }
                .sortedWith(compareByDescending<TroubleField> { it.rate }.thenByDescending { it.asked }.thenBy { it.label })
                .take(MAX_TROUBLE)

            val steps = current.flatMap { s -> s.screens.flatMap { it.steps } }
            return Insights(
                days = period,
                runs = current.size,
                completed = current.count { it.status == RunStatus.COMPLETED },
                filledByVoice = steps.count { it.outcome in VOICE },
                typedByHand = steps.count { it.outcome == StepOutcome.MANUAL },
                totalMillis = current.sumOf { it.durationMillis.coerceAtLeast(0) },
                daily = daily,
                apps = apps,
                troubleFields = trouble,
                previousRuns = previous.size,
                previousCompleted = previous.count { it.status == RunStatus.COMPLETED },
            )
        }
    }
}
