package com.voicecontrol.feature.flows

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.voicecontrol.core.engine.FlowSimulator
import com.voicecontrol.core.engine.FlowSimulator.EntryKind
import com.voicecontrol.core.model.FlowDefinition
import java.time.LocalDate

/**
 * A test run of a flow on the phone: the user types (or taps) the answers they would say and sees
 * what would be filled and pressed, without opening the app. Uses [FlowSimulator], the same walk as
 * the dashboard's test mode and the backend dry-run API.
 */
data class DryRunSession(
    val flow: FlowDefinition,
    val profile: Map<String, String>,
    val today: LocalDate,
    val answers: List<String> = emptyList(),
    val result: FlowSimulator.Result = FlowSimulator.simulate(flow.steps, answers, profile) { today },
) {
    /** Quick answers for the pending question. */
    val suggestions: List<String>
        get() {
            val pending = result.pending ?: return emptyList()
            return when {
                pending.expects == FlowSimulator.Expects.YESNO -> listOf("Yes", "No")
                pending.question.contains("Say yes to use") -> listOf("Yes", "No", "Skip")
                else -> listOf("Skip")
            }
        }

    fun answer(text: String): DryRunSession {
        val a = text.trim()
        if (a.isEmpty() || result.pending == null) return this
        return copy(answers = answers + a, result = FlowSimulator.simulate(flow.steps, answers + a, profile) { today })
    }

    fun undo(): DryRunSession =
        if (answers.isEmpty()) this else answers.dropLast(1).let { copy(answers = it, result = FlowSimulator.simulate(flow.steps, it, profile) { today }) }

    fun restart(): DryRunSession = copy(answers = emptyList(), result = FlowSimulator.simulate(flow.steps, emptyList(), profile) { today })

    companion object {
        fun start(flow: FlowDefinition, profile: Map<String, String>, today: LocalDate = LocalDate.now()) = DryRunSession(flow, profile, today)
    }
}

@Composable
internal fun DryRunPane(session: DryRunSession, modifier: Modifier, onIntent: (FlowsIntent) -> Unit) {
    val result = session.result
    val list = rememberLazyListState()
    LaunchedEffect(result.transcript.size) { if (result.transcript.isNotEmpty()) list.animateScrollToItem(result.transcript.size) }
    Column(modifier) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = list,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                Text(
                    "Test run: answer like you would by voice. Nothing is typed or pressed in the app.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(result.transcript) { entry -> TranscriptLine(entry) }
        }
        HorizontalDivider()
        val pending = result.pending
        Column(
            Modifier.fillMaxWidth().padding(12.dp).semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (pending != null) {
                Text(pending.question, style = MaterialTheme.typography.titleMedium)
                var text by remember(session.answers.size) { mutableStateOf("") }
                val send = {
                    onIntent(FlowsIntent.DryRunAnswer(text))
                    text = ""
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it.take(500) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text(if (pending.expects == FlowSimulator.Expects.YESNO) "Yes or no" else "Your answer") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { send() }),
                    )
                    IconButton(onClick = send, enabled = text.isNotBlank()) { Icon(Icons.AutoMirrored.Filled.Send, "Answer") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    session.suggestions.forEach { s -> AssistChip(onClick = { onIntent(FlowsIntent.DryRunAnswer(s)) }, label = { Text(s) }) }
                    Spacer(Modifier.weight(1f))
                    if (session.answers.isNotEmpty()) {
                        TextButton(onClick = { onIntent(FlowsIntent.DryRunUndo) }) {
                            Icon(Icons.AutoMirrored.Filled.Undo, null)
                            Spacer(Modifier.width(4.dp))
                            Text("Undo")
                        }
                    }
                }
            } else {
                Text("Test run finished: ${result.values.size} fields would be set.", style = MaterialTheme.typography.titleMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onIntent(FlowsIntent.DryRunRestart) }) {
                    Icon(Icons.Filled.Replay, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Start over")
                }
                TextButton(onClick = { onIntent(FlowsIntent.CloseDryRun) }) { Text("Close") }
            }
        }
    }
}

@Composable
private fun TranscriptLine(entry: FlowSimulator.Entry) {
    val colors = MaterialTheme.colorScheme
    val (label, color) = when (entry.kind) {
        EntryKind.SCREEN -> "Screen" to colors.tertiary
        EntryKind.ASK -> "Asks" to colors.primary
        EntryKind.ANSWER -> "You" to colors.secondary
        EntryKind.FILL -> "Fills" to colors.primary
        EntryKind.PRESS -> "Presses" to colors.primary
        EntryKind.SKIP -> "Skips" to colors.onSurfaceVariant
        EntryKind.VARIABLE -> "Remembers" to colors.onSurfaceVariant
        EntryKind.ERROR -> "Problem" to colors.error
        EntryKind.MANUAL -> "You type" to colors.onSurfaceVariant
        EntryKind.DONE -> "Done" to colors.tertiary
    }
    val mine = entry.kind == EntryKind.ANSWER
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier
                .background(if (mine) colors.secondaryContainer else Color.Transparent, RoundedCornerShape(8.dp))
                .padding(horizontal = if (mine) 10.dp else 0.dp, vertical = 2.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = color)
            Text(entry.text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
