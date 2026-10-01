package com.voicecontrol.feature.settings

import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.voicecontrol.core.data.memory.AnswerMemoryRepository
import com.voicecontrol.core.data.memory.RememberedAnswer
import com.voicecontrol.core.data.settings.SettingsRepository
import com.voicecontrol.core.ui.components.EmptyState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RememberedState(val enabled: Boolean = false, val answers: List<RememberedAnswer> = emptyList())

/** Answers grouped by app, apps with the most recent answer first. */
fun groupByApp(answers: List<RememberedAnswer>): List<Pair<String, List<RememberedAnswer>>> =
    answers.groupBy { it.appPackage }.toList().sortedByDescending { (_, list) -> list.maxOf { it.updatedAtMillis } }

@HiltViewModel
class RememberedAnswersViewModel @Inject constructor(
    private val memory: AnswerMemoryRepository,
    settings: SettingsRepository,
) : ViewModel() {
    val state = combine(settings.settings, memory.answers) { s, list -> RememberedState(s.rememberAnswers, list) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RememberedState())

    fun forget(answer: RememberedAnswer) = viewModelScope.launch { memory.forget(answer.appPackage, answer.key) }
    fun forgetAll() = viewModelScope.launch { memory.forgetAll() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RememberedAnswersRoute(onBack: () -> Unit, viewModel: RememberedAnswersViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmAll by remember { mutableStateOf(false) }
    val packages = LocalContext.current.packageManager
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Remembered answers") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (state.answers.isNotEmpty()) TextButton(onClick = { confirmAll = true }) { Text("Forget all") }
                },
            )
        },
    ) { padding ->
        if (state.answers.isEmpty()) {
            EmptyState(
                if (state.enabled) "Nothing remembered yet. Answers you give are offered again next time on the same field."
                else "Turn on “Remember my answers” in Settings to have VoiceControl offer what you said last time.",
                Modifier.fillMaxSize().padding(padding),
                Icons.Filled.History,
            )
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text(
                    "Kept only on this phone. Never passwords, OTPs or PINs.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            groupByApp(state.answers).forEach { (pkg, list) ->
                item(key = "app-$pkg") { Text(appName(packages, pkg), style = MaterialTheme.typography.titleSmall) }
                items(list, key = { "${it.appPackage}/${it.key}" }) { answer ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                                Text(answer.label, style = MaterialTheme.typography.bodyMedium)
                                Text(answer.value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { viewModel.forget(answer) }) { Icon(Icons.Filled.Delete, "Forget ${answer.label}") }
                        }
                    }
                }
            }
        }
    }
    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text("Forget all remembered answers?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmAll = false
                    viewModel.forgetAll()
                }) { Text("Forget all") }
            },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text("Cancel") } },
        )
    }
}

private fun appName(packages: PackageManager, pkg: String): String =
    runCatching { packages.getApplicationLabel(packages.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
