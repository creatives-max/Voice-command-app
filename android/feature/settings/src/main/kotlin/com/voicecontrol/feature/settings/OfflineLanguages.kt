package com.voicecontrol.feature.settings

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
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.voicecontrol.core.data.settings.SettingsRepository
import com.voicecontrol.core.engine.LanguagePack
import com.voicecontrol.core.engine.OfflineLanguages
import com.voicecontrol.core.engine.SpeechPackState
import com.voicecontrol.core.engine.VoiceState
import com.voicecontrol.core.engine.port.LanguagePacks
import com.voicecontrol.core.engine.port.PackDownload
import com.voicecontrol.core.model.Language
import com.voicecontrol.core.ui.components.LoadingBox
import com.voicecontrol.core.ui.mvi.CollectEffects
import com.voicecontrol.core.ui.mvi.MviViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

data class OfflineLanguagesState(
    val loading: Boolean = true,
    val packs: List<LanguagePack> = emptyList(),
    val offlineSpeech: Boolean = false,
    val localOnly: Boolean = false,
    val current: Language = Language.ENGLISH,
)

sealed interface OfflineLanguagesIntent {
    data object Refresh : OfflineLanguagesIntent
    data class SetOfflineSpeech(val on: Boolean) : OfflineLanguagesIntent
    data class DownloadSpeech(val language: Language) : OfflineLanguagesIntent
    data class InstallVoice(val language: Language) : OfflineLanguagesIntent
}

/** Words for pack states (also used in tests). */
object PackText {
    fun speech(state: SpeechPackState) = when (state) {
        SpeechPackState.INSTALLED -> "Listening works offline"
        SpeechPackState.DOWNLOADING -> "Downloading…"
        SpeechPackState.AVAILABLE -> "Listening needs internet (download available)"
        SpeechPackState.UNSUPPORTED -> "Listening needs internet (no offline pack for this phone)"
        SpeechPackState.UNKNOWN -> "Offline listening: check in your phone's voice settings"
    }

    fun voice(state: VoiceState) = when (state) {
        VoiceState.INSTALLED -> "Speaking works offline"
        VoiceState.NEEDS_DOWNLOAD -> "Voice needs internet (download available)"
        VoiceState.UNSUPPORTED -> "No voice for this language on this phone"
        VoiceState.UNKNOWN -> "Voice: unknown"
    }

    fun download(result: PackDownload, language: Language, what: String) = when (result) {
        PackDownload.STARTED -> "Downloading ${language.nativeName} $what in the background. Keep the phone online for a few minutes."
        PackDownload.OPENED_SETTINGS -> "Choose ${language.nativeName} there to download it."
        PackDownload.UNSUPPORTED -> "This phone can't download it here. Look under Settings, System, Languages."
    }
}

@HiltViewModel
class OfflineLanguagesViewModel @Inject constructor(
    private val packs: LanguagePacks,
    private val settings: SettingsRepository,
) : MviViewModel<OfflineLanguagesState, OfflineLanguagesIntent, String>(OfflineLanguagesState()) {

    init {
        settings.settings.onEach { s ->
            setState { copy(offlineSpeech = s.offlineSpeech, localOnly = s.localOnly, current = s.language) }
        }.launchIn(viewModelScope)
    }

    override suspend fun handleIntent(intent: OfflineLanguagesIntent) {
        when (intent) {
            OfflineLanguagesIntent.Refresh -> {
                val list = runCatching { packs.status() }.getOrDefault(emptyList())
                setState { copy(loading = false, packs = list) }
            }
            is OfflineLanguagesIntent.SetOfflineSpeech -> settings.update { it.copy(offlineSpeech = intent.on) }
            is OfflineLanguagesIntent.DownloadSpeech -> {
                sendEffect(PackText.download(packs.downloadSpeech(intent.language), intent.language, "speech"))
                handleIntent(OfflineLanguagesIntent.Refresh)
            }
            is OfflineLanguagesIntent.InstallVoice -> sendEffect(PackText.download(packs.installVoice(intent.language), intent.language, "voice"))
        }
    }
}

@Composable
fun OfflineLanguagesRoute(onBack: () -> Unit, viewModel: OfflineLanguagesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectEffects(viewModel.effects) { snackbar.showSnackbar(it) }
    // Downloads finish in other apps (Play services, the TTS engine): check again when we come back.
    LifecycleResumeEffect(Unit) {
        viewModel.dispatch(OfflineLanguagesIntent.Refresh)
        onPauseOrDispose { }
    }
    OfflineLanguagesScreen(state, snackbar, onBack, viewModel::dispatch)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineLanguagesScreen(state: OfflineLanguagesState, snackbar: SnackbarHostState, onBack: () -> Unit, onIntent: (OfflineLanguagesIntent) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Offline languages") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { IconButton(onClick = { onIntent(OfflineLanguagesIntent.Refresh) }) { Icon(Icons.Filled.Refresh, "Check again") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (state.loading) {
            LoadingBox(Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CloudOff, null)
                    Text(OfflineLanguages.summary(state.packs), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.titleMedium)
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Speech on the phone")
                        Text(
                            if (state.localOnly) "Always on in On-device only mode." else "Use downloaded packs even when online. Without internet VoiceControl switches by itself.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.offlineSpeech || state.localOnly,
                        enabled = !state.localOnly,
                        onCheckedChange = { onIntent(OfflineLanguagesIntent.SetOfflineSpeech(it)) },
                        modifier = Modifier.semantics { contentDescription = "Speech on the phone" },
                    )
                }
            }
            items(state.packs, key = { it.language.name }) { pack -> PackCard(pack, pack.language == state.current, onIntent) }
            item {
                Text(
                    "Understanding answers (numbers, dates, yes/no, commands) always works offline in every language; only listening and speaking need these packs.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PackCard(pack: LanguagePack, inUse: Boolean, onIntent: (OfflineLanguagesIntent) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(pack.language.nativeName, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                if (inUse) AssistChip(onClick = {}, label = { Text("In use") })
                if (pack.worksOffline) AssistChip(onClick = {}, label = { Text("Works offline") })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(PackText.speech(pack.speech), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                if (pack.canDownloadSpeech) {
                    OutlinedButton(onClick = { onIntent(OfflineLanguagesIntent.DownloadSpeech(pack.language)) }) { Text("Download") }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(PackText.voice(pack.voice), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                if (pack.canDownloadVoice) {
                    OutlinedButton(onClick = { onIntent(OfflineLanguagesIntent.InstallVoice(pack.language)) }) { Text("Get voice") }
                }
            }
        }
    }
}
