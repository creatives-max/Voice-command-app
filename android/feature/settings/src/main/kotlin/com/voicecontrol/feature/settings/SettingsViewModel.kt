package com.voicecontrol.feature.settings

import androidx.lifecycle.viewModelScope
import com.voicecontrol.core.data.settings.SettingsRepository
import com.voicecontrol.core.engine.port.TextToSpeech
import com.voicecontrol.core.nlp.WakeWord
import com.voicecontrol.core.ui.mvi.MviViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val tts: TextToSpeech,
    private val account: com.voicecontrol.core.data.account.AccountDataRepository,
    private val stt: com.voicecontrol.core.engine.port.SpeechToText,
    private val memory: com.voicecontrol.core.data.memory.AnswerMemoryRepository,
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
                    wakeWordDraft = if (loaded) wakeWordDraft else s.wakeWord,
                )
            }
        }.launchIn(viewModelScope)
    }

    override suspend fun handleIntent(intent: SettingsIntent) {
        when (intent) {
            is SettingsIntent.SetLanguage -> repository.update { it.copy(language = intent.language) }
            is SettingsIntent.SetSpeechRate -> repository.update { it.copy(speechRate = intent.rate.coerceIn(0.5f, 2f)) }
            is SettingsIntent.SetThemeMode -> repository.update { it.copy(themeMode = intent.mode) }
            is SettingsIntent.Toggle -> {
                repository.update { it.with(intent.option, intent.enabled) }
                if (intent.option == Option.REMEMBER_ANSWERS && !intent.enabled) {
                    memory.forgetAll()
                    sendEffect(SettingsEffect.Message("Remembered answers deleted from this phone"))
                }
            }
            SettingsIntent.TestWakeWord -> {
                val phrase = currentState.wakeWordDraft.trim().ifEmpty { currentState.settings.wakeWord }
                if (!WakeWord.isValidPhrase(phrase)) {
                    sendEffect(SettingsEffect.Message("Choose a longer wake phrase, like “Hey VoiceControl”"))
                    return
                }
                setState { copy(testingWake = true) }
                val result = stt.listen(
                    com.voicecontrol.core.engine.port.ListenRequest(currentState.settings.language.speechTag, preferOffline = true),
                )
                setState { copy(testingWake = false) }
                sendEffect(SettingsEffect.Message(wakeTestMessage(result, phrase)))
            }
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
            is SettingsIntent.EditWakeWord -> setState { copy(wakeWordDraft = intent.value.take(60)) }
            SettingsIntent.SaveWakeWord -> {
                val phrase = currentState.wakeWordDraft.trim()
                if (!WakeWord.isValidPhrase(phrase)) {
                    sendEffect(SettingsEffect.Message("Choose a longer wake phrase, like “Hey VoiceControl”"))
                    return
                }
                repository.update { it.copy(wakeWord = phrase) }
                sendEffect(SettingsEffect.Message("Wake phrase saved"))
            }
            is SettingsIntent.SetAppLock -> repository.update { it.copy(appLock = intent.enabled) }
            is SettingsIntent.SetLockTimeout -> repository.update { it.copy(lockTimeoutSeconds = intent.seconds) }
            is SettingsIntent.ExportData -> {
                setState { copy(busy = true) }
                val result = runCatching { account.exportTo(intent.target) }
                setState { copy(busy = false) }
                sendEffect(
                    SettingsEffect.Message(
                        result.fold(
                            onSuccess = { if (it.serverIncluded) "Saved your data from this phone and your account" else "Saved your data from this phone" },
                            onFailure = { "Export failed: ${it.message}" },
                        ),
                    ),
                )
            }
            SettingsIntent.WipePhone -> {
                setState { copy(busy = true) }
                account.wipePhone()
                setState { copy(busy = false) }
                sendEffect(SettingsEffect.PhoneWiped)
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
