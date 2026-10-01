package com.voicecontrol.feature.settings

import androidx.lifecycle.viewModelScope
import com.voicecontrol.core.data.settings.SettingsRepository
import com.voicecontrol.core.engine.port.TextToSpeech
import com.voicecontrol.core.ui.mvi.MviViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val tts: TextToSpeech,
) : MviViewModel<SettingsState, SettingsIntent, SettingsEffect>(SettingsState()) {

    init {
        repository.settings.onEach { s ->
            setState {
                copy(
                    loaded = true,
                    settings = s,
                    backendUrlDraft = if (loaded) backendUrlDraft else s.backendUrl,
                    dashboardUrlDraft = if (loaded) dashboardUrlDraft else s.dashboardUrl,
                    deviceNameDraft = if (loaded) deviceNameDraft else s.deviceName,
                )
            }
        }.launchIn(viewModelScope)
    }

    override suspend fun handleIntent(intent: SettingsIntent) {
        when (intent) {
            is SettingsIntent.SetLanguage -> repository.update { it.copy(language = intent.language) }
            is SettingsIntent.SetSpeechRate -> repository.update { it.copy(speechRate = intent.rate.coerceIn(0.5f, 2f)) }
            is SettingsIntent.Toggle -> repository.update { it.with(intent.option, intent.enabled) }
            is SettingsIntent.EditBackendUrl -> setState { copy(backendUrlDraft = intent.value) }
            is SettingsIntent.EditDashboardUrl -> setState { copy(dashboardUrlDraft = intent.value) }
            SettingsIntent.SaveUrls -> {
                val backend = currentState.backendUrlDraft
                val dashboard = currentState.dashboardUrlDraft
                if (!isValidServerUrl(backend) || !isValidServerUrl(dashboard)) {
                    sendEffect(SettingsEffect.Message("Enter a full address like https://api.example.com"))
                    return
                }
                repository.update { it.copy(backendUrl = backend, dashboardUrl = dashboard) }
                sendEffect(SettingsEffect.Message("Server addresses saved"))
            }
            is SettingsIntent.EditDeviceName -> setState { copy(deviceNameDraft = intent.value.take(60)) }
            SettingsIntent.SaveDeviceName -> {
                repository.update { it.copy(deviceName = currentState.deviceNameDraft) }
                sendEffect(SettingsEffect.Message("Phone name saved. The dashboard shows it after the next sync."))
            }
            SettingsIntent.TestVoice -> {
                val s = currentState.settings
                if (!tts.speak(sampleSentence(s.language), s.language.voiceTag, s.speechRate)) {
                    sendEffect(SettingsEffect.Message("Text-to-speech is not available. Install a voice in Android settings."))
                }
            }
        }
    }

    override fun onCleared() {
        tts.stop()
        super.onCleared()
    }
}
