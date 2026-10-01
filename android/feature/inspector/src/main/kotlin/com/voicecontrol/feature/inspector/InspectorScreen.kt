package com.voicecontrol.feature.inspector

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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voicecontrol.core.ui.components.EmptyState
import com.voicecontrol.core.ui.mvi.CollectEffects
import java.text.DateFormat
import java.util.Date

@Composable
fun InspectorRoute(onBack: () -> Unit, viewModel: InspectorViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectEffects(viewModel.effects) { effect ->
        when (effect) {
            is InspectorEffect.Message -> snackbar.showSnackbar(effect.text)
        }
    }
    InspectorScreen(state, snackbar, onBack, viewModel::dispatch)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InspectorScreen(
    state: InspectorState,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onIntent: (InspectorIntent) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Screen inspector") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { onIntent(InspectorIntent.Refresh) }, enabled = !state.refreshing) {
                        Icon(Icons.Filled.Refresh, "Refresh")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val snapshot = state.snapshot
        if (snapshot == null) {
            EmptyState(
                if (state.serviceConnected) "Open any app, then come back here to see its fields and buttons."
                else "Turn on the VoiceControl accessibility service to inspect screens.",
                Modifier.padding(padding),
                Icons.Filled.Search,
            )
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(snapshot.title ?: snapshot.packageName, style = MaterialTheme.typography.titleLarge)
                    Text(
                        listOfNotNull(snapshot.packageName, snapshot.activityName?.substringAfterLast('.')).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "${snapshot.fields.size} fields · ${snapshot.buttons.size} buttons · " +
                            DateFormat.getTimeInstance().format(Date(snapshot.capturedAtMillis)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    FilterChip(
                        selected = state.showButtons,
                        onClick = { onIntent(InspectorIntent.ToggleButtons(!state.showButtons)) },
                        label = { Text("Show buttons") },
                    )
                }
            }
            items(state.rows, key = { it.id }) { row -> ElementCard(row) }
        }
    }
}

@Composable
private fun ElementCard(row: ElementRow) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (row.isSensitive) {
                    Icon(Icons.Filled.Lock, "Sensitive", tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(6.dp))
                }
                Text(row.title, style = MaterialTheme.typography.titleMedium)
            }
            Text(row.kindLabel + if (!row.enabled) " · disabled" else "", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            row.valueLabel?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Text(row.id, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.outline)
        }
    }
}
