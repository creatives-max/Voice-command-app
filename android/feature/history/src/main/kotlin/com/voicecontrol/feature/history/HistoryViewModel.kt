package com.voicecontrol.feature.history

import androidx.lifecycle.viewModelScope
import com.voicecontrol.core.data.history.HistoryRepository
import com.voicecontrol.core.data.settings.SettingsRepository
import com.voicecontrol.core.model.Insights
import com.voicecontrol.core.model.SessionSummary
import com.voicecontrol.core.ui.mvi.MviViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.time.Clock
import javax.inject.Inject

/** Enough sessions for 90 days of insights plus the 90 days before them. */
private const val INSIGHTS_HISTORY_LIMIT = 2_000

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val history: HistoryRepository,
    settings: SettingsRepository,
) : MviViewModel<HistoryState, HistoryIntent, HistoryEffect>(HistoryState()) {

    init {
        combine(history.observeRecent(limit = INSIGHTS_HISTORY_LIMIT), settings.settings) { sessions, prefs -> sessions to prefs.saveHistory }
            .onEach { (sessions, enabled) ->
                setState {
                    copy(
                        loading = false,
                        sessions = sessions,
                        historyEnabled = enabled,
                        insights = insightsOf(sessions, insightDays),
                        selected = selected?.let { s -> sessions.firstOrNull { it.sessionId == s.sessionId } },
                    )
                }
            }
            .launchIn(viewModelScope)
    }

    override suspend fun handleIntent(intent: HistoryIntent) {
        when (intent) {
            is HistoryIntent.SelectTab -> setState { copy(tab = intent.tab, selected = null) }
            is HistoryIntent.SetInsightDays -> setState { copy(insightDays = intent.days, insights = insightsOf(sessions, intent.days)) }
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

    private fun insightsOf(sessions: List<SessionSummary>, days: Int): Insights {
        val clock = Clock.systemDefaultZone()
        return Insights.of(sessions, days, clock.instant(), clock.zone)
    }
}
