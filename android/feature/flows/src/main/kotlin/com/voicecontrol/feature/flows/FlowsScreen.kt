package com.voicecontrol.feature.flows

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.VerticalDivider
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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
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
import com.voicecontrol.core.model.FlowVariables
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
    BackHandler(enabled = state.editing != null) { viewModel.dispatch(FlowsIntent.CancelEdit) }
    BackHandler(enabled = state.selected != null && state.editing == null) { viewModel.dispatch(FlowsIntent.CloseDetail) }
    FlowsScreen(state, snackbar, onBack, viewModel::dispatch)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlowsScreen(state: FlowsState, snackbar: SnackbarHostState, onBack: () -> Unit, onIntent: (FlowsIntent) -> Unit) {
    val selected = state.selected
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.editing != null) "Edit flow" else selected?.name ?: "Saved flows") },
                navigationIcon = {
                    IconButton(onClick = {
                        when {
                            state.editing != null -> onIntent(FlowsIntent.CancelEdit)
                            selected != null -> onIntent(FlowsIntent.CloseDetail)
                            else -> onBack()
                        }
                    }) {
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
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            // Tablets and unfolded phones: list and detail side by side.
            val twoPane = maxWidth >= 840.dp
            when {
                state.loading -> LoadingBox()
                twoPane -> Row(Modifier.fillMaxSize()) {
                    FlowList(state, Modifier.weight(0.42f).fillMaxHeight(), onIntent)
                    VerticalDivider()
                    Box(Modifier.weight(0.58f).fillMaxHeight()) {
                        if (selected != null) {
                            FlowPane(state, selected, onIntent)
                        } else {
                            EmptyState("Choose a flow to see and edit its steps.", Modifier.fillMaxSize(), Icons.Filled.ViewList)
                        }
                    }
                }
                selected != null -> FlowPane(state, selected, onIntent)
                state.apps.isEmpty() && state.templates.isEmpty() -> EmptyState(
                    "No flows yet. Run VoiceControl on any app; each form you fill is saved here and can be edited on the dashboard.",
                    Modifier.fillMaxSize(),
                    Icons.Filled.ViewList,
                )
                else -> FlowList(state, Modifier.fillMaxSize(), onIntent)
            }
        }
    }
}

@Composable
private fun FlowPane(state: FlowsState, selected: FlowDefinition, onIntent: (FlowsIntent) -> Unit) {
    val draft = state.editing
    if (draft != null && draft.id == selected.id) {
        FlowEditor(draft, state.saving, Modifier.fillMaxSize(), onIntent)
    } else {
        FlowDetail(selected, selected.id in state.appOpenFlowIds, Modifier.fillMaxSize(), onIntent)
    }
}

@Composable
private fun FlowList(state: FlowsState, modifier: Modifier, onIntent: (FlowsIntent) -> Unit) {
    LazyColumn(
        modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.apps.forEach { app ->
            item(key = "app-${app.appPackage}") {
                Text(app.appPackage, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
            }
            items(app.flows, key = { it.id }) { flow -> FlowCard(flow, flow.id == state.selected?.id) { onIntent(FlowsIntent.Open(flow.id)) } }
        }
        if (state.apps.isEmpty()) {
            item(key = "no-flows") {
                Text(
                    "No saved flows yet. Run VoiceControl on any app; each form you fill is saved here and can be edited here or on the dashboard.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (state.templates.isNotEmpty()) {
            item(key = "templates-header") {
                Column(Modifier.padding(top = 16.dp)) {
                    Text("Starter templates", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Text(
                        "Used automatically on screens without a saved flow when they fit. Turn off in Settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(state.templates, key = { "t-" + it.id }) { t -> TemplateCard(t) }
        }
    }
}

/** Editing on the phone: name, questions, defaults, skips, order and removing steps. */
@Composable
private fun FlowEditor(draft: FlowDefinition, saving: Boolean, modifier: Modifier, onIntent: (FlowsIntent) -> Unit) {
    val steps = draft.orderedSteps
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            OutlinedTextField(
                value = draft.name,
                onValueChange = { onIntent(FlowsIntent.Rename(it)) },
                label = { Text("Flow name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Text(
                if (draft.isSynced) "Saving adds a new version to your account (on-device only: kept on this phone)." else "Saved on this phone; synced when you sign in.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        itemsIndexed(steps, key = { _, s -> s.id }) { index, step ->
            EditableStep(step, index, steps.size, onIntent)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onIntent(FlowsIntent.SaveEdit) }, enabled = !saving) {
                    Icon(Icons.Filled.Save, null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (saving) "Saving…" else "Save")
                }
                OutlinedButton(onClick = { onIntent(FlowsIntent.CancelEdit) }, enabled = !saving) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun EditableStep(step: FlowStep, index: Int, count: Int, onIntent: (FlowsIntent) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${index + 1}. ${step.label}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = { onIntent(FlowsIntent.MoveStep(step.id, -1)) }, enabled = index > 0) { Icon(Icons.Filled.ArrowUpward, "Move ${step.label} up") }
                IconButton(onClick = { onIntent(FlowsIntent.MoveStep(step.id, 1)) }, enabled = index < count - 1) { Icon(Icons.Filled.ArrowDownward, "Move ${step.label} down") }
                IconButton(onClick = { onIntent(FlowsIntent.RemoveStep(step.id)) }) { Icon(Icons.Filled.Delete, "Remove ${step.label}") }
            }
            if (step.action == StepAction.FILL || step.action == StepAction.CLICK) {
                OutlinedTextField(
                    value = step.question.orEmpty(),
                    onValueChange = { onIntent(FlowsIntent.SetQuestion(step.id, it)) },
                    label = { Text(if (step.action == StepAction.CLICK) "Confirmation question" else "Question") },
                    placeholder = { Text("Generated from the label") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (step.action == StepAction.FILL && step.fieldType?.isSensitive != true) {
                OutlinedTextField(
                    value = step.defaultValue.orEmpty(),
                    onValueChange = { onIntent(FlowsIntent.SetDefault(step.id, it)) },
                    label = { Text("Default value") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else if (step.fieldType?.isSensitive == true) {
                Text("Typed by you every time (passwords, OTPs and PINs are never saved).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (step.action == StepAction.CLICK) "Press without asking" else "Skip this step", modifier = Modifier.weight(1f))
                Switch(checked = step.skip, onCheckedChange = { onIntent(FlowsIntent.SetSkip(step.id, it)) })
            }
        }
    }
}

@Composable
private fun TemplateCard(template: TemplateSummary) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(template.name, style = MaterialTheme.typography.titleMedium)
            Text(template.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${template.stepCount} steps", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun FlowCard(flow: FlowDefinition, selected: Boolean, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = if (selected) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else CardDefaults.cardColors(),
    ) {
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
private fun FlowDetail(flow: FlowDefinition, startsOnAppOpen: Boolean, modifier: Modifier, onIntent: (FlowsIntent) -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(
                if (flow.isSynced) "Version ${flow.version}. Edit questions, defaults, skips and order here, or everything (rules, logic, help videos) on the web dashboard; the next run uses your edits."
                else "Recorded on this phone. Edit it here, or sign in to sync it and edit it on the web dashboard.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { onIntent(FlowsIntent.Run(flow)) }) {
                    Icon(Icons.Filled.PlayArrow, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Run now")
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { onIntent(FlowsIntent.StartEdit) }) {
                    Icon(Icons.Filled.Edit, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Edit")
                }
                if (startsOnAppOpen) {
                    Spacer(Modifier.width(8.dp))
                    AssistChip(onClick = {}, label = { Text("Starts when the app opens") })
                }
            }
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
                    StepAction.READ -> "Read into {${FlowVariables.nameOf(step)}}"
                    StepAction.SET_VARIABLE -> "Set {${FlowVariables.nameOf(step)}} = ${step.valueExpression.orEmpty()}"
                    StepAction.REPEAT -> "Repeat ${step.repeat?.stepIds?.size ?: 0} steps for each ${step.repeat?.itemLabel ?: "item"}"
                    StepAction.NEXT_SCREEN -> "Wait for the next screen"
                    StepAction.OPEN_APP -> "Open ${step.appPackage.orEmpty()}"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            step.condition?.takeIf { it.isNotBlank() }?.let { Text("Only if: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
            step.elseValue?.takeIf { it.isNotBlank() }?.let { Text("Otherwise: $it", style = MaterialTheme.typography.bodySmall) }
            step.valueExpression?.takeIf { it.isNotBlank() && step.action != StepAction.SET_VARIABLE }?.let {
                Text("Computed: $it", style = MaterialTheme.typography.bodySmall)
            }
            step.question?.let { Text("“$it”", style = MaterialTheme.typography.bodyMedium) }
            step.defaultValue?.let { Text("Default: $it", style = MaterialTheme.typography.bodySmall) }
            if (step.rules.isNotEmpty()) Text("Rules: ${step.rules.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
            step.helpVideoUrl?.let { Text("Help video: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) }
        }
    }
}
