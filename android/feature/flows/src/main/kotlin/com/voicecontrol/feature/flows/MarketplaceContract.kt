package com.voicecontrol.feature.flows

import com.voicecontrol.core.data.flows.MarketplaceUnavailable
import com.voicecontrol.core.model.FlowStep
import com.voicecontrol.core.model.StepAction
import com.voicecontrol.core.network.dto.ListingDetailDto
import com.voicecontrol.core.network.dto.ListingDto
import java.util.Locale

data class MarketplaceState(
    val loading: Boolean = true,
    val unavailable: MarketplaceUnavailable? = null,
    val query: String = "",
    val category: String? = null,
    val sort: MarketSort = MarketSort.POPULAR,
    val categories: List<String> = emptyList(),
    val results: List<ListingDto> = emptyList(),
    val canLoadMore: Boolean = false,
    val detail: ListingDetailDto? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

enum class MarketSort(val api: String, val label: String) {
    POPULAR("popular", "Most used"),
    RATING("rating", "Best rated"),
    RECENT("recent", "Newest"),
}

sealed interface MarketplaceIntent {
    data class EditQuery(val text: String) : MarketplaceIntent
    data object Search : MarketplaceIntent
    data class ChooseCategory(val category: String?) : MarketplaceIntent
    data class ChooseSort(val sort: MarketSort) : MarketplaceIntent
    data object LoadMore : MarketplaceIntent
    data class Open(val id: String) : MarketplaceIntent
    data object CloseDetail : MarketplaceIntent
    data object Install : MarketplaceIntent
    data class Rate(val stars: Int, val review: String) : MarketplaceIntent
    data class Report(val reason: String, val note: String) : MarketplaceIntent
}

/** Text shown for listings (also used by tests). */
object MarketText {
    val reportReasons = listOf(
        "broken" to "It doesn't work",
        "unsafe" to "Unsafe or misleading",
        "spam" to "Spam or advertising",
        "other" to "Something else",
    )

    fun category(id: String): String = id.replace('_', ' ').replaceFirstChar { it.titlecase(Locale.getDefault()) }

    fun rating(average: Double?, count: Int): String =
        if (average == null || count == 0) "No ratings yet" else "★ %.1f (%d)".format(Locale.US, average, count)

    fun installs(count: Int): String = when (count) {
        0 -> "New"
        1 -> "1 install"
        else -> "$count installs"
    }

    /** One line per step: what VoiceControl does there. */
    fun step(step: FlowStep): String = when (step.action) {
        StepAction.FILL -> "Asks for ${step.label}" + if (step.skip) " (skipped)" else ""
        StepAction.CLICK -> "Presses ${step.label}"
        StepAction.TOGGLE -> "Sets ${step.label}"
        StepAction.READ -> "Reads ${step.label} from the screen"
        StepAction.SET_VARIABLE -> "Works out ${step.variable ?: "a value"}"
        StepAction.REPEAT -> "Repeats for each ${step.repeat?.itemLabel ?: "item"}"
        StepAction.NEXT_SCREEN -> "Waits for the next screen"
        StepAction.OPEN_APP -> "Opens ${step.appPackage ?: "another app"}"
    }

    fun reported(myReport: String?): String? =
        myReport?.lowercase()?.let { id -> reportReasons.firstOrNull { it.first == id }?.second ?: id }

    fun installLabel(detail: ListingDetailDto): String = when {
        detail.updateAvailable -> "Update to version ${detail.listing.latestVersion}"
        detail.importedFlowId != null -> "Installed (version ${detail.importedVersion ?: detail.listing.latestVersion})"
        else -> "Install"
    }
}
