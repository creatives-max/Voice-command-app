package com.voicecontrol.feature.history

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voicecontrol.core.model.RunStatus
import com.voicecontrol.core.model.SessionSummary
import com.voicecontrol.core.ui.components.EmptyState
import com.voicecontrol.core.ui.components.LoadingBox
import com.voicecontrol.core.ui.components.SectionCard
import com.voicecontrol.core.ui.mvi.CollectEffects
import java.text.DateFormat
import java.util.Date

@Composable
fun HistoryRoute(onBack: () -> Unit, openInsights: Boolean = false, viewModel: HistoryViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Opened from the launcher's "Insights" shortcut: start on that tab (once per visit).
    var insightsOpened by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(openInsights) {
        if (openInsights && !insightsOpened) {
            insightsOpened = true
            viewModel.dispatch(HistoryIntent.SelectTab(HistoryTab.INSIGHTS))
        }
    }
    val snackbar = remember { SnackbarHostState() }
    CollectEffects(viewModel.effects) { effect ->
        when (effect) {
            is HistoryEffect.Message -> snackbar.showSnackbar(effect.text)
        }
    }
    BackHandler(enabled = state.selected != null) { viewModel.dispatch(HistoryIntent.CloseDetail) }
    HistoryScreen(state, snackbar, onBack, viewModel::dispatch)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(state: HistoryState, snackbar: SnackbarHostState, onBack: () -> Unit, onIntent: (HistoryIntent) -> Unit) {
    var confirmClear by remember { mutableStateOf(false) }
    val selected = state.selected
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selected != null) selected.appPackage else "History") },
                navigationIcon = {
                    IconButton(onClick = { if (selected != null) onIntent(HistoryIntent.CloseDetail) else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (selected == null && state.sessions.isNotEmpty()) {
                        IconButton(onClick = { confirmClear = true }) { Icon(Icons.Filled.DeleteSweep, "Clear history") }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { scaffoldPadding ->
        val showTabs = !state.loading && selected == null && state.sessions.isNotEmpty()
        Column(Modifier.fillMaxSize().padding(scaffoldPadding)) {
            if (showTabs) {
                TabRow(selectedTabIndex = state.tab.ordinal) {
                    Tab(selected = state.tab == HistoryTab.SESSIONS, onClick = { onIntent(HistoryIntent.SelectTab(HistoryTab.SESSIONS)) }, text = { Text("Sessions") })
                    Tab(selected = state.tab == HistoryTab.INSIGHTS, onClick = { onIntent(HistoryIntent.SelectTab(HistoryTab.INSIGHTS)) }, text = { Text("Insights") })
                }
            }
            HistoryBody(state, selected, Modifier.weight(1f), onIntent)
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear history on this phone?") },
            text = { Text("Sessions already synced to your account stay there; delete them from the dashboard.") },
            confirmButton = { TextButton(onClick = { confirmClear = false; onIntent(HistoryIntent.ClearAll) }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun HistoryBody(state: HistoryState, selected: SessionSummary?, modifier: Modifier, onIntent: (HistoryIntent) -> Unit) {
    val insights = state.insights
    when {
        state.loading -> LoadingBox(modifier)
        selected != null -> SessionDetail(selected, modifier) { onIntent(HistoryIntent.Delete(selected.sessionId)) }
        state.sessions.isEmpty() -> EmptyState(
            if (state.historyEnabled) "No voice sessions yet." else "History is turned off in Settings.",
            modifier,
            Icons.Filled.History,
        )
        state.tab == HistoryTab.INSIGHTS && insights != null -> InsightsContent(insights, { onIntent(HistoryIntent.SetInsightDays(it)) }, modifier)
        else -> LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                val t = state.totals
                SectionCard(
                    title = "${t.sessions} sessions · ${t.fieldsFilled} fields filled",
                    subtitle = "${t.completed} completed. Values you spoke are never stored, only what happened to each field.",
                )
            }
            items(state.sessions, key = { it.sessionId }) { s -> SessionRow(s) { onIntent(HistoryIntent.Open(s.sessionId)) } }
        }
    }
}

@Composable
private fun StatusIcon(status: RunStatus) {
    when (status) {
        RunStatus.COMPLETED -> Icon(Icons.Filled.CheckCircle, "Completed", tint = MaterialTheme.colorScheme.primary)
        RunStatus.STOPPED -> Icon(Icons.Filled.StopCircle, "Stopped", tint = MaterialTheme.colorScheme.outline)
        RunStatus.FAILED -> Icon(Icons.Filled.ErrorOutline, "Failed", tint = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun SessionRow(s: SessionSummary, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatusIcon(s.status)
            Column(Modifier.weight(1f)) {
                Text(s.appPackage, style = MaterialTheme.typography.titleSmall)
                Text(
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(s.startedAtMillis)) +
                        " · ${s.filledCount} filled · ${formatDuration(s.durationMillis)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SessionDetail(s: SessionSummary, modifier: Modifier, onDelete: () -> Unit) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusIcon(s.status)
                Text("${s.status.name.lowercase().replaceFirstChar { it.uppercase() }} · ${s.language.name.lowercase()} · ${formatDuration(s.durationMillis)}")
            }
        }
        s.screens.forEachIndexed { index, screen ->
            item(key = "screen-$index") {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(screen.screenTitle ?: screen.activityName?.substringAfterLast('.') ?: "Screen ${index + 1}", style = MaterialTheme.typography.titleSmall)
                        if (screen.flowId != null) {
                            Text("Saved flow v${screen.flowVersion ?: 1}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        screen.steps.forEach { step ->
                            Row {
                                Text(step.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                Text(step.outcome.label(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
        item { TextButton(onClick = onDelete) { Text("Delete this session") } }
    }
}
