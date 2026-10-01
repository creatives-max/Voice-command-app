package com.voicecontrol.feature.flows

import androidx.lifecycle.viewModelScope
import com.voicecontrol.core.data.flows.FlowLibrary
import com.voicecontrol.core.data.settings.SettingsRepository
import com.voicecontrol.core.ui.mvi.MviViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

@HiltViewModel
class FlowsViewModel @Inject constructor(
    private val library: FlowLibrary,
    private val settings: SettingsRepository,
) : MviViewModel<FlowsState, FlowsIntent, FlowsEffect>(FlowsState()) {

    init {
        library.observe().onEach { flows ->
            setState {
                copy(
                    loading = false,
                    apps = groupByApp(flows),
                    selected = selected?.let { s -> flows.firstOrNull { it.id == s.id } },
                )
            }
        }.launchIn(viewModelScope)
    }

    override suspend fun handleIntent(intent: FlowsIntent) {
        when (intent) {
            is FlowsIntent.Open -> setState { copy(selected = apps.flatMap { it.flows }.firstOrNull { it.id == intent.flowId }) }
            FlowsIntent.CloseDetail -> setState { copy(selected = null) }
            is FlowsIntent.Delete -> {
                library.delete(intent.flow).fold(
                    onSuccess = {
                        setState { copy(selected = null) }
                        sendEffect(FlowsEffect.Message("Flow deleted"))
                    },
                    onFailure = { sendEffect(FlowsEffect.Message("Couldn't delete: ${it.message}")) },
                )
            }
            FlowsIntent.Sync -> {
                library.syncNow()
                sendEffect(FlowsEffect.Message("Syncing with your account…"))
            }
            FlowsIntent.OpenDashboard -> sendEffect(FlowsEffect.OpenUrl(settings.dashboardUrl()))
        }
    }
}
