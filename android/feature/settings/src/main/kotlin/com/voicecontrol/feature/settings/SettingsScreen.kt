package com.voicecontrol.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voicecontrol.core.model.Language
import com.voicecontrol.core.ui.components.LoadingBox
import com.voicecontrol.core.ui.mvi.CollectEffects

@Composable
fun SettingsRoute(onBack: () -> Unit, onOpenPrivacy: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectEffects(viewModel.effects) { effect ->
        when (effect) {
            is SettingsEffect.Message -> snackbar.showSnackbar(effect.text)
        }
    }
    SettingsScreen(state, snackbar, onBack, onOpenPrivacy, viewModel::dispatch)
}

private val toggles = listOf(
    Triple(Option.SHOW_OVERLAY, "Show the floating mic", "The bubble appears over other apps while the service is on."),
    Triple(Option.CONFIRM_VALUES, "Read back answers", "Say \"Got it, …\" after filling so you can correct mistakes."),
    Triple(Option.ASK_BEFORE_SUBMIT, "Ask before pressing submit", "Confirm before the final button of a form."),
    Triple(Option.SKIP_FILLED, "Skip fields that already have a value", "Otherwise VoiceControl asks whether to keep them."),
    Triple(Option.TRANSLITERATE, "Type Hindi names in English letters", "राहुल → Rahul for name, email and address fields."),
    Triple(Option.AUTO_START, "Start automatically on saved screens", "Begin asking when an app with a saved flow opens."),
    Triple(Option.USE_TEMPLATES, "Use starter templates", "On new screens, use the sign-up, login or address template that fits."),
    Triple(Option.REMOTE_RUNS, "Allow runs from the dashboard", "\"Run now\" and schedules can start flows on this phone. Needs sign-in."),
    Triple(Option.SAVE_HISTORY, "Keep session history", "What happened to each field, never the values."),
    Triple(Option.LOCAL_ONLY, "Local-only mode", "Never contact the server: on-device understanding only, no sync."),
    Triple(Option.VISION_FALLBACK, "Screenshot fallback", "For apps with no readable fields, send a screenshot to find fields and buttons."),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsState,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onIntent: (SettingsIntent) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (!state.loaded) {
            LoadingBox(Modifier.padding(padding))
            return@Scaffold
        }
        val s = state.settings
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Language", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Language.entries.forEach { lang ->
                    FilterChip(
                        selected = s.language == lang,
                        onClick = { onIntent(SettingsIntent.SetLanguage(lang)) },
                        label = { Text(lang.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    )
                }
            }
            Text("Speaking speed: ${"%.1f".format(s.speechRate)}×", style = MaterialTheme.typography.bodyMedium)
            Slider(value = s.speechRate, onValueChange = { onIntent(SettingsIntent.SetSpeechRate(it)) }, valueRange = 0.5f..2f, steps = 5)
            OutlinedButton(onClick = { onIntent(SettingsIntent.TestVoice) }) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, null)
                Spacer(Modifier.width(8.dp))
                Text("Test voice")
            }
            HorizontalDivider()
            toggles.forEach { (option, title, subtitle) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.bodyLarge)
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = s.isOn(option), onCheckedChange = { onIntent(SettingsIntent.Toggle(option, it)) })
                }
            }
            OutlinedTextField(
                value = state.deviceNameDraft,
                onValueChange = { onIntent(SettingsIntent.EditDeviceName(it)) },
                label = { Text("Phone name in the dashboard") },
                placeholder = { Text(android.os.Build.MODEL) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = { onIntent(SettingsIntent.SaveDeviceName) }) { Text("Save phone name") }
            HorizontalDivider()
            Text("Servers", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = state.backendUrlDraft,
                onValueChange = { onIntent(SettingsIntent.EditBackendUrl(it)) },
                label = { Text("Backend address") },
                placeholder = { Text("Default for this build") },
                singleLine = true,
                isError = !isValidServerUrl(state.backendUrlDraft),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.dashboardUrlDraft,
                onValueChange = { onIntent(SettingsIntent.EditDashboardUrl(it)) },
                label = { Text("Dashboard address") },
                placeholder = { Text("Default for this build") },
                singleLine = true,
                isError = !isValidServerUrl(state.dashboardUrlDraft),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { onIntent(SettingsIntent.SaveUrls) }) { Text("Save addresses") }
            HorizontalDivider()
            OutlinedButton(onClick = onOpenPrivacy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.PrivacyTip, null)
                Spacer(Modifier.width(8.dp))
                Text("Privacy policy")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Privacy policy") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PrivacyPolicy.sections.forEach { (title, body) ->
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(body, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
