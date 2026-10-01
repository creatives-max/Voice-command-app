package com.voicecontrol.feature.flows

import androidx.lifecycle.viewModelScope
import com.voicecontrol.core.data.flows.FlowMarketplace
import com.voicecontrol.core.ui.mvi.MviViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MarketplaceViewModel @Inject constructor(private val market: FlowMarketplace) :
    MviViewModel<MarketplaceState, MarketplaceIntent, String>(MarketplaceState()) {

    init {
        viewModelScope.launch {
            val unavailable = market.unavailable()
            if (unavailable != null) {
                setState { copy(loading = false, unavailable = unavailable) }
                return@launch
            }
            market.categories().onSuccess { setState { copy(categories = it) } }
            search(reset = true)
        }
    }

    private suspend fun search(reset: Boolean) {
        val s = currentState
        setState { copy(loading = reset, busy = !reset) }
        market.search(s.query, s.category, if (s.query.isNotBlank() && reset) null else s.sort.api, if (reset) 0 else s.results.size).fold(
            onSuccess = { page ->
                setState {
                    copy(
                        loading = false, busy = false, error = null,
                        results = if (reset) page else results + page.filter { new -> results.none { it.id == new.id } },
                        canLoadMore = page.size == FlowMarketplace.PAGE,
                    )
                }
            },
            onFailure = { setState { copy(loading = false, busy = false, error = "Couldn't reach the marketplace. Check the internet connection.") } },
        )
    }

    private suspend fun reloadDetail(id: String) {
        market.detail(id).onSuccess { d -> setState { copy(detail = d) } }
    }

    override suspend fun handleIntent(intent: MarketplaceIntent) {
        when (intent) {
            is MarketplaceIntent.EditQuery -> setState { copy(query = intent.text.take(100)) }
            MarketplaceIntent.Search -> search(reset = true)
            is MarketplaceIntent.ChooseCategory -> {
                setState { copy(category = intent.category) }
                search(reset = true)
            }
            is MarketplaceIntent.ChooseSort -> {
                setState { copy(sort = intent.sort) }
                search(reset = true)
            }
            MarketplaceIntent.LoadMore -> if (currentState.canLoadMore && !currentState.busy) search(reset = false)
            is MarketplaceIntent.Open -> {
                setState { copy(busy = true) }
                market.detail(intent.id).fold(
                    onSuccess = { d -> setState { copy(busy = false, detail = d) } },
                    onFailure = {
                        setState { copy(busy = false) }
                        sendEffect("This flow is no longer available")
                    },
                )
            }
            MarketplaceIntent.CloseDetail -> setState { copy(detail = null) }
            MarketplaceIntent.Install -> {
                val d = currentState.detail ?: return
                setState { copy(busy = true) }
                market.install(d.listing.id).fold(
                    onSuccess = { flow ->
                        sendEffect("“${flow.name}” is in your flows. It runs when you open ${d.listing.appPackage.ifBlank { "its app" }}.")
                        reloadDetail(d.listing.id)
                    },
                    onFailure = { sendEffect(it.message ?: "Couldn't install") },
                )
                setState { copy(busy = false) }
            }
            is MarketplaceIntent.Rate -> {
                val d = currentState.detail ?: return
                if (intent.stars !in 1..5) return
                market.rate(d.listing.id, intent.stars, intent.review.trim().take(500)).fold(
                    onSuccess = {
                        sendEffect("Thanks for rating")
                        reloadDetail(d.listing.id)
                    },
                    onFailure = { sendEffect(it.message ?: "Couldn't save your rating") },
                )
            }
            is MarketplaceIntent.Report -> {
                val d = currentState.detail ?: return
                market.report(d.listing.id, intent.reason, intent.note.trim().take(500)).fold(
                    onSuccess = { hidden ->
                        sendEffect(if (hidden) "Thanks. This flow is now hidden while its author fixes it." else "Thanks for the report")
                        reloadDetail(d.listing.id)
                    },
                    onFailure = { sendEffect(it.message ?: "Couldn't send the report") },
                )
            }
        }
    }
}
