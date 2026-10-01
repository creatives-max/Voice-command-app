package com.voicecontrol.feature.flows

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/** Voice macros of one flow: phrases that run it, added here or on the dashboard. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ShortcutsCard(shortcuts: List<ShortcutItem>, onIntent: (FlowsIntent) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.RecordVoiceOver, null)
                Text("Voice shortcuts", style = MaterialTheme.typography.titleSmall)
            }
            Text(
                "Tap the mic (or say your wake word) on any screen without a form and say a phrase to run this flow.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                shortcuts.forEach { item ->
                    InputChip(
                        selected = false,
                        onClick = {},
                        label = { Text("“${item.phrase}”" + if (item.local) "" else " · dashboard") },
                        trailingIcon = if (item.local) {
                            {
                                Icon(
                                    Icons.Filled.Close,
                                    "Remove “${item.phrase}”",
                                    Modifier.size(InputChipDefaults.IconSize).clickable { onIntent(FlowsIntent.RemoveShortcut(item)) },
                                )
                            }
                        } else {
                            null
                        },
                    )
                }
            }
            var phrase by remember { mutableStateOf("") }
            val add = {
                if (phrase.isNotBlank()) {
                    onIntent(FlowsIntent.AddShortcut(phrase))
                    phrase = ""
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = phrase,
                    onValueChange = { phrase = it.take(60) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("New phrase, like “pay electricity bill”") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { add() }),
                )
                TextButton(onClick = add, enabled = phrase.isNotBlank()) { Text("Add") }
            }
        }
    }
}
