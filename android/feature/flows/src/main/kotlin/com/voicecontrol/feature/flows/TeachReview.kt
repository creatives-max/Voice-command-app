package com.voicecontrol.feature.flows

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.voicecontrol.core.data.teach.TaughtFlows
import com.voicecontrol.core.engine.Recording
import com.voicecontrol.core.engine.RecordingToFlow
import com.voicecontrol.core.model.StepAction
import com.voicecontrol.core.ui.components.EmptyState
import com.voicecontrol.core.ui.mvi.CollectEffects
import com.voicecontrol.core.ui.mvi.MviViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

/** One recorded step as shown for review. */
data class ReviewStep(val elementId: String, val title: String, val detail: String, val value: String?, val screen: Int)

data class TeachReviewState(
    val loaded: Boolean = false,
    val steps: List<ReviewStep> = emptyList(),
    val name: String = "",
    /** Element ids whose typed text becomes the step's default value. */
    val keep: Set<String> = emptySet(),
    val saving: Boolean = false,
)

sealed interface TeachReviewIntent {
    data class Rename(val name: String) : TeachReviewIntent
    data class Keep(val elementId: String, val keep: Boolean) : TeachReviewIntent
    data object Save : TeachReviewIntent
    data object Discard : TeachReviewIntent
}

sealed interface TeachReviewEffect {
    data class Done(val message: String) : TeachReviewEffect
}

/** Rows for the review screen: every recorded action plus where the screen changes. */
fun reviewSteps(recording: Recording): List<ReviewStep> = recording.screens.flatMapIndexed { index, screen ->
    val header = if (index == 0) {
        emptyList()
    } else {
        val other = recording.screens[index - 1].snapshot.packageName != screen.snapshot.packageName
        listOf(ReviewStep("", if (other) "Switch to ${screen.snapshot.packageName}" else "Next screen", screen.snapshot.title.orEmpty(), null, index))
    }
    header + screen.actions.map { a ->
        val label = a.element.label.ifBlank { a.element.hint.orEmpty() }.ifBlank { a.element.id }
        ReviewStep(
            a.element.id,
            when (a.action) {
                StepAction.CLICK -> "Press “$label”"
                StepAction.TOGGLE -> "Switch “$label”"
                else -> "Fill “$label”"
            },
            if (a.action == StepAction.FILL) (if (a.element.isSensitive || a.element.fieldType?.isSensitive == true) "Typed by you each time" else "Asked by voice") else "",
            a.value,
            index,
        )
    }
}

@HiltViewModel
class TeachReviewViewModel @Inject constructor(private val taught: TaughtFlows) :
    MviViewModel<TeachReviewState, TeachReviewIntent, TeachReviewEffect>(TeachReviewState()) {

    init {
        taught.pending.onEach { r ->
            setState {
                if (r == null) {
                    copy(loaded = true, steps = emptyList())
                } else {
                    copy(loaded = true, steps = reviewSteps(r), name = name.ifBlank { RecordingToFlow.defaultName(r.screens.first().snapshot) })
                }
            }
        }.launchIn(viewModelScope)
    }

    override suspend fun handleIntent(intent: TeachReviewIntent) {
        when (intent) {
            is TeachReviewIntent.Rename -> setState { copy(name = intent.name.take(120)) }
            is TeachReviewIntent.Keep -> setState { copy(keep = if (intent.keep) keep + intent.elementId else keep - intent.elementId) }
            TeachReviewIntent.Save -> {
                setState { copy(saving = true) }
                val result = runCatching { taught.save(currentState.name, currentState.keep) }
                setState { copy(saving = false) }
                result.fold(
                    onSuccess = { sendEffect(TeachReviewEffect.Done("Saved “${it.name}”. VoiceControl will ask these questions next time.")) },
                    onFailure = { sendEffect(TeachReviewEffect.Done(it.message ?: "Couldn't save")) },
                )
            }
            TeachReviewIntent.Discard -> {
                taught.discard()
                sendEffect(TeachReviewEffect.Done("Recording discarded"))
            }
        }
    }
}

@Composable
fun TeachReviewRoute(onDone: (String) -> Unit, viewModel: TeachReviewViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectEffects(viewModel.effects) { effect ->
        when (effect) {
            is TeachReviewEffect.Done -> onDone(effect.message)
        }
    }
    TeachReviewScreen(state, snackbar, viewModel::dispatch)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeachReviewScreen(state: TeachReviewState, snackbar: SnackbarHostState, onIntent: (TeachReviewIntent) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Review what you taught") },
                navigationIcon = { IconButton(onClick = { onIntent(TeachReviewIntent.Discard) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Discard") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (state.loaded && state.steps.isEmpty()) {
            EmptyState("Nothing was recorded. Start teaching from Saved flows, then fill a form by touch.", Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text(
                    "VoiceControl will do these steps in this order, asking you by voice for each field. Values you typed are only kept if you tick them.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                OutlinedTextField(
                    value = state.name,
                    onValueChange = { onIntent(TeachReviewIntent.Rename(it)) },
                    label = { Text("Flow name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            items(state.steps, key = { "${it.screen}-${it.elementId}-${it.title}" }) { step ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(step.title, style = MaterialTheme.typography.titleSmall)
                            if (step.detail.isNotBlank()) Text(step.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            step.value?.let { Text("You typed: $it", style = MaterialTheme.typography.bodySmall) }
                        }
                        if (step.value != null) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Checkbox(checked = step.elementId in state.keep, onCheckedChange = { onIntent(TeachReviewIntent.Keep(step.elementId, it)) })
                                Text("Default", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onIntent(TeachReviewIntent.Save) }, enabled = !state.saving && state.name.isNotBlank()) { Text("Save flow") }
                    OutlinedButton(onClick = { onIntent(TeachReviewIntent.Discard) }, enabled = !state.saving) { Text("Discard") }
                }
            }
        }
    }
}
