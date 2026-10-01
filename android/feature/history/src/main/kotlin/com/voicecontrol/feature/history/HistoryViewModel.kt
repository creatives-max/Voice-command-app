package com.voicecontrol.feature.history

import androidx.lifecycle.viewModelScope
import com.voicecontrol.core.data.history.HistoryRepository
import com.voicecontrol.core.data.settings.SettingsRepository
import com.voicecontrol.core.ui.mvi.MviViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val history: HistoryRepository,
    settings: SettingsRepository,
) : MviViewModel<HistoryState, HistoryIntent, HistoryEffect>(HistoryState()) {

    init {
        combine(history.observeRecent(), settings.settings) { sessions, prefs -> sessions to prefs.saveHistory }
            .onEach { (sessions, enabled) ->
                setState {
                    copy(
                        loading = false,
                        sessions = sessions,
                        historyEnabled = enabled,
                        selected = selected?.let { s -> sessions.firstOrNull { it.sessionId == s.sessionId } },
                    )
                }
            }
            .launchIn(viewModelScope)
    }

    override suspend fun handleIntent(intent: HistoryIntent) {
        when (intent) {
            is HistoryIntent.Open -> setState { copy(selected = sessions.firstOrNull { it.sessionId == intent.sessionId }) }
            HistoryIntent.CloseDetail -> setState { copy(selected = null) }
            is HistoryIntent.Delete -> {
                history.delete(intent.sessionId)
                setState { copy(selected = null) }
                sendEffect(HistoryEffect.Message("Session deleted from this phone"))
            }
            HistoryIntent.ClearAll -> {
                history.clear()
                sendEffect(HistoryEffect.Message("History cleared on this phone"))
            }
        }
    }
}
