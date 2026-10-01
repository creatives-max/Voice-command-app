package com.voicecontrol.feature.assistant.scan

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScanScreen(viewModel: ScanViewModel, onTakePhoto: () -> Unit, onPickPhoto: () -> Unit, onFill: () -> Unit, onClose: () -> Unit) {
    val stage by viewModel.stage.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Fill from a photo") },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when (val s = stage) {
                ScanStage.Choose -> Choose(onTakePhoto, onPickPhoto)
                ScanStage.Reading -> Column(
                    Modifier.fillMaxSize().semantics { liveRegion = LiveRegionMode.Polite },
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.padding(8.dp))
                    Text("Reading the document on your phone…")
                }
                is ScanStage.Review -> Review(s, viewModel, onFill, onRetry = viewModel::retry)
                is ScanStage.Problem -> Column(Modifier.padding(24.dp).semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(s.message, style = MaterialTheme.typography.bodyLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = viewModel::retry) { Text("Try another photo") }
                        TextButton(onClick = onClose) { Text("Close") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Choose(onTakePhoto: () -> Unit, onPickPhoto: () -> Unit) {
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(Icons.Filled.DocumentScanner, null, tint = MaterialTheme.colorScheme.primary)
        Text("Take a photo of a document — PAN, Aadhaar, driving licence, voter ID, passport, cheque, bill or letter — and VoiceControl fills the matching fields.")
        Text(
            "The photo is read on this phone, never uploaded, and deleted right after. You check every value before anything is filled.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onTakePhoto, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.PhotoCamera, null)
            Spacer(Modifier.width(8.dp))
            Text("Take a photo")
        }
        OutlinedButton(onClick = onPickPhoto, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.PhotoLibrary, null)
            Spacer(Modifier.width(8.dp))
            Text("Choose a photo")
        }
    }
}

@Composable
private fun Review(stage: ScanStage.Review, viewModel: ScanViewModel, onFill: () -> Unit, onRetry: () -> Unit) {
    val count = ScanReview.chosen(stage.items).size
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text("From your ${stage.document.type.label}. Check each value; untick what you don't want filled.", style = MaterialTheme.typography.bodyMedium)
            }
            itemsIndexed(stage.items, key = { _, item -> item.fill.element.id }) { index, item ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = item.checked, onCheckedChange = { viewModel.toggle(index) })
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(item.fill.element.label.ifBlank { item.fill.field.label }, style = MaterialTheme.typography.titleSmall)
                            if (item.revealed) {
                                OutlinedTextField(
                                    value = item.value,
                                    onValueChange = { viewModel.edit(index, it) },
                                    singleLine = item.fill.field != com.voicecontrol.core.engine.DocField.ADDRESS,
                                    label = { Text(item.fill.field.label) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(item.shown, Modifier.weight(1f))
                                    TextButton(onClick = { viewModel.reveal(index) }) {
                                        Icon(Icons.Filled.Visibility, null)
                                        Spacer(Modifier.width(4.dp))
                                        Text("Show")
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (stage.unused.isNotEmpty()) {
                item {
                    Text(
                        "Also found (no matching field here): " + stage.unused.entries.joinToString { (field, value) ->
                            "${field.label} ${com.voicecontrol.core.engine.DocumentFieldMapper.display(field, value)}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onFill, enabled = count > 0, modifier = Modifier.weight(1f)) {
                Text(if (count == 1) "Fill 1 field" else "Fill $count fields")
            }
            OutlinedButton(onClick = onRetry) { Text("Another photo") }
        }
    }
}
