package com.voicecontrol.feature.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.voicecontrol.core.model.DayRuns
import com.voicecontrol.core.model.Insights
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.abs
import kotlin.math.roundToInt

private val dayFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

/** "+20% vs the 30 days before", "new", or null when there is nothing to compare. */
internal fun runsChange(current: Int, previous: Int, days: Int): String? = when {
    previous == 0 && current == 0 -> null
    previous == 0 -> "New in the last $days days"
    else -> {
        val pct = ((current - previous) * 100.0 / previous).roundToInt()
        val sign = if (pct > 0) "+" else if (pct < 0) "−" else "±"
        "$sign${abs(pct)}% vs the $days days before"
    }
}

internal fun percent(rate: Double?): String = rate?.let { "${(it * 100).roundToInt()}%" } ?: "–"

/** Usage and success from this phone's own history, with the fields where voice struggled. */
@Composable
internal fun InsightsContent(insights: Insights, onDays: (Int) -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Insights.PERIODS.forEach { d ->
                    FilterChip(selected = insights.days == d, onClick = { onDays(d) }, label = { Text("$d days") })
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat("Sessions", insights.runs.toString(), runsChange(insights.runs, insights.previousRuns, insights.days), Modifier.weight(1f))
                Stat("Completed", percent(insights.successRate), insights.previousSuccessRate?.let { "Before: ${percent(it)}" }, Modifier.weight(1f))
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat("Filled by voice", insights.filledByVoice.toString(), insights.voiceShare?.let { "${percent(it)} of answers" }, Modifier.weight(1f))
                Stat("Time with VoiceControl", formatDuration(insights.totalMillis), null, Modifier.weight(1f))
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Sessions per day", style = MaterialTheme.typography.titleSmall)
                    DailyBars(insights.daily, Modifier.fillMaxWidth().height(120.dp))
                    val busiest = insights.busiestDay
                    Text(
                        if (busiest == null) "No sessions in this period." else "Busiest day: ${busiest.date.format(dayFormat)} (${busiest.total})",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        if (insights.apps.isNotEmpty()) {
            item { Text("Apps", style = MaterialTheme.typography.titleSmall) }
            items(insights.apps, key = { "app-${it.appPackage}" }) { app ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(app.appPackage, style = MaterialTheme.typography.bodyLarge)
                        val rate = if (app.runs == 0) 0f else app.completed.toFloat() / app.runs
                        LinearProgressIndicator(progress = { rate }, modifier = Modifier.fillMaxWidth())
                        Text("${app.runs} sessions · ${app.completed} completed", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (insights.troubleFields.isNotEmpty()) {
            item {
                Column {
                    Text("Fields that need work", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "You often typed these by hand or skipped them. Edit the flow's question or add a default.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(insights.troubleFields, key = { "field-${it.appPackage}-${it.label}" }) { f ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(f.label, style = MaterialTheme.typography.bodyLarge)
                        Text("${f.appPackage} · ${f.trouble} of ${f.asked} times by hand or skipped", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        item {
            Text(
                "Worked out on this phone from its history; nothing extra is sent anywhere.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Stat(label: String, value: String, note: String?, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineSmall)
            if (note != null) Text(note, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Stacked bars per day (completed, stopped, failed); read out as a summary by screen readers. */
@Composable
private fun DailyBars(daily: List<DayRuns>, modifier: Modifier) {
    val completed = MaterialTheme.colorScheme.primary
    val stopped = MaterialTheme.colorScheme.tertiary
    val failed = MaterialTheme.colorScheme.error
    val max = (daily.maxOfOrNull { it.total } ?: 0).coerceAtLeast(1)
    val summary = "${daily.sumOf { it.total }} sessions over ${daily.size} days; at most $max in a day"
    Canvas(modifier.semantics { contentDescription = summary }) {
        if (daily.isEmpty()) return@Canvas
        val slot = size.width / daily.size
        val barWidth = (slot * 0.7f).coerceAtLeast(1f)
        daily.forEachIndexed { i, d ->
            var top = size.height
            listOf(d.completed to completed, d.stopped to stopped, d.failed to failed).forEach { (n, color: Color) ->
                if (n > 0) {
                    val h = size.height * n / max
                    top -= h
                    drawRect(color, topLeft = Offset(i * slot + (slot - barWidth) / 2, top), size = Size(barWidth, h))
                }
            }
        }
    }
}
