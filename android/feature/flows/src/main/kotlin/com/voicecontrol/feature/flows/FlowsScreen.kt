package com.voicecontrol.feature.flows

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voicecontrol.core.model.FlowDefinition
import com.voicecontrol.core.model.FlowStep
import com.voicecontrol.core.model.StepAction
import com.voicecontrol.core.ui.components.EmptyState
import com.voicecontrol.core.ui.components.LoadingBox
import com.voicecontrol.core.ui.mvi.CollectEffects
import java.text.DateFormat
import java.util.Date

@Composable
fun FlowsRoute(onBack: () -> Unit, viewModel: FlowsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    CollectEffects(viewModel.effects) { effect ->
        when (effect) {
            is FlowsEffect.Message -> snackbar.showSnackbar(effect.text)
            is FlowsEffect.OpenUrl -> runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(effect.url)))
            }.onFailure { snackbar.showSnackbar("No browser available") }
        }
    }
    BackHandler(enabled = state.selected != null) { viewModel.dispatch(FlowsIntent.CloseDetail) }
    FlowsScreen(state, snackbar, onBack, viewModel::dispatch)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlowsScreen(state: FlowsState, snackbar: SnackbarHostState, onBack: () -> Unit, onIntent: (FlowsIntent) -> Unit) {
    val selected = state.selected
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(selected?.name ?: "Saved flows") },
                navigationIcon = {
                    IconButton(onClick = { if (selected != null) onIntent(FlowsIntent.CloseDetail) else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { onIntent(FlowsIntent.OpenDashboard) }) { Icon(Icons.Filled.OpenInBrowser, "Open dashboard") }
                    IconButton(onClick = { onIntent(FlowsIntent.Sync) }) { Icon(Icons.Filled.Sync, "Sync") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when {
            state.loading -> LoadingBox(Modifier.padding(padding))
            selected != null -> FlowDetail(selected, Modifier.padding(padding), onIntent)
            state.apps.isEmpty() -> EmptyState(
                "No flows yet. Run VoiceControl on any app; each form you fill is saved here and can be edited on the dashboard.",
                Modifier.padding(padding),
                Icons.Filled.ViewList,
            )
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.apps.forEach { app ->
                    item(key = "app-${app.appPackage}") {
                        Text(app.appPackage, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
                    }
                    items(app.flows, key = { it.id }) { flow -> FlowCard(flow) { onIntent(FlowsIntent.Open(flow.id)) } }
                }
            }
        }
    }
}

@Composable
private fun FlowCard(flow: FlowDefinition, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(flow.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${flow.steps.size} steps · v${flow.version} · " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(flow.updatedAtMillis)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                if (flow.isSynced) Icons.Filled.CloudDone else Icons.Filled.CloudUpload,
                contentDescription = if (flow.isSynced) "Synced" else "Not synced yet",
                tint = if (flow.isSynced) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun FlowDetail(flow: FlowDefinition, modifier: Modifier, onIntent: (FlowsIntent) -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(
                if (flow.isSynced) "Version ${flow.version}. Edit questions, rules, defaults, skips, order and help videos on the web dashboard; the next run uses your edits."
                else "Recorded on this phone. Sign in to sync it and edit it on the web dashboard.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(flow.orderedSteps, key = { it.id }) { step -> StepCard(step) }
        item {
            Row {
                TextButton(onClick = { confirmDelete = true }) {
                    Icon(Icons.Filled.Delete, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Delete flow")
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this flow?") },
            text = { Text("It will be removed from this phone" + if (flow.isSynced) " and from your account." else ".") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onIntent(FlowsIntent.Delete(flow)) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun StepCard(step: FlowStep) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${step.order + 1}. ${step.label}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (step.skip) AssistChip(onClick = {}, label = { Text("Skipped") })
            }
            Text(
                when (step.action) {
                    StepAction.CLICK -> "Press button"
                    StepAction.TOGGLE -> "Toggle"
                    StepAction.FILL -> step.fieldType?.name?.lowercase()?.replace('_', ' ') ?: "text"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            step.question?.let { Text("“$it”", style = MaterialTheme.typography.bodyMedium) }
            step.defaultValue?.let { Text("Default: $it", style = MaterialTheme.typography.bodySmall) }
            if (step.rules.isNotEmpty()) Text("Rules: ${step.rules.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
            step.helpVideoUrl?.let { Text("Help video: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) }
        }
    }
}
