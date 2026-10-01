package com.voicecontrol.feature.flows

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voicecontrol.core.data.flows.MarketplaceUnavailable
import com.voicecontrol.core.network.dto.ListingDetailDto
import com.voicecontrol.core.network.dto.ListingDto
import com.voicecontrol.core.ui.components.EmptyState
import com.voicecontrol.core.ui.components.LoadingBox
import com.voicecontrol.core.ui.mvi.CollectEffects

@Composable
fun MarketplaceRoute(onBack: () -> Unit, onSignIn: () -> Unit, viewModel: MarketplaceViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectEffects(viewModel.effects) { snackbar.showSnackbar(it) }
    BackHandler(enabled = state.detail != null) { viewModel.dispatch(MarketplaceIntent.CloseDetail) }
    MarketplaceScreen(state, snackbar, onBack, onSignIn, viewModel::dispatch)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketplaceScreen(state: MarketplaceState, snackbar: SnackbarHostState, onBack: () -> Unit, onSignIn: () -> Unit, onIntent: (MarketplaceIntent) -> Unit) {
    val detail = state.detail
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(detail?.listing?.name ?: "Get flows") },
                navigationIcon = {
                    IconButton(onClick = { if (detail != null) onIntent(MarketplaceIntent.CloseDetail) else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val modifier = Modifier.fillMaxSize().padding(padding)
        when {
            state.unavailable != null -> Column(modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                EmptyState(
                    if (state.unavailable == MarketplaceUnavailable.ON_DEVICE_ONLY) {
                        "The marketplace needs the VoiceControl server. Turn off “On-device only” in Settings to use it."
                    } else {
                        "Sign in to get flows other people made for apps you use."
                    },
                    Modifier.fillMaxWidth(),
                    Icons.Filled.Storefront,
                )
                if (state.unavailable == MarketplaceUnavailable.SIGNED_OUT) Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) { Text("Sign in") }
            }
            detail != null -> ListingDetail(detail, state.busy, modifier, onIntent)
            else -> Browse(state, modifier, onIntent)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Browse(state: MarketplaceState, modifier: Modifier, onIntent: (MarketplaceIntent) -> Unit) {
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            OutlinedTextField(
                value = state.query,
                onValueChange = { onIntent(MarketplaceIntent.EditQuery(it)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                label = { Text("Search apps or flows") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onIntent(MarketplaceIntent.Search) }),
            )
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MarketSort.entries.forEach { sort ->
                    FilterChip(selected = state.sort == sort, onClick = { onIntent(MarketplaceIntent.ChooseSort(sort)) }, label = { Text(sort.label) })
                }
            }
        }
        if (state.categories.isNotEmpty()) {
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = state.category == null, onClick = { onIntent(MarketplaceIntent.ChooseCategory(null)) }, label = { Text("All") })
                    state.categories.forEach { c ->
                        FilterChip(selected = state.category == c, onClick = { onIntent(MarketplaceIntent.ChooseCategory(c)) }, label = { Text(MarketText.category(c)) })
                    }
                }
            }
        }
        state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        when {
            state.loading -> item { LoadingBox(Modifier.fillMaxWidth().padding(32.dp)) }
            state.results.isEmpty() -> item { EmptyState("No flows found. Try another search or category.", Modifier.fillMaxWidth(), Icons.Filled.Storefront) }
            else -> itemsIndexed(state.results, key = { _, l -> l.id }) { index, listing ->
                if (index == state.results.lastIndex && state.canLoadMore) {
                    LaunchedEffect(state.results.size) { onIntent(MarketplaceIntent.LoadMore) }
                }
                ListingCard(listing) { onIntent(MarketplaceIntent.Open(listing.id)) }
            }
        }
    }
}

@Composable
private fun ListingCard(listing: ListingDto, onOpen: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClickLabel = "Open ${listing.name}", onClick = onOpen)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(listing.name, style = MaterialTheme.typography.titleMedium)
            if (listing.description.isNotBlank()) Text(listing.description, style = MaterialTheme.typography.bodySmall, maxLines = 2)
            Text(
                listOf(MarketText.category(listing.category), listing.appPackage, MarketText.rating(listing.ratingAverage, listing.ratingCount), MarketText.installs(listing.installCount))
                    .filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ListingDetail(detail: ListingDetailDto, busy: Boolean, modifier: Modifier, onIntent: (MarketplaceIntent) -> Unit) {
    val listing = detail.listing
    var reporting by remember { mutableStateOf(false) }
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(listing.description.ifBlank { "No description." })
            Text(
                listOfNotNull(listing.appPackage, listing.ownerName?.let { "by $it" }, "version ${listing.latestVersion}", MarketText.rating(listing.ratingAverage, listing.ratingCount))
                    .joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!listing.mine) {
                    Button(onClick = { onIntent(MarketplaceIntent.Install) }, enabled = !busy && (detail.importedFlowId == null || detail.updateAvailable)) {
                        Icon(Icons.Filled.Download, null)
                        Spacer(Modifier.width(6.dp))
                        Text(MarketText.installLabel(detail))
                    }
                    TextButton(onClick = { reporting = true }) {
                        Icon(Icons.Filled.Flag, null)
                        Spacer(Modifier.width(4.dp))
                        Text(MarketText.reported(detail.myReport)?.let { "Reported: $it" } ?: "Report")
                    }
                } else {
                    Text("You shared this flow.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { Text("What it does", style = MaterialTheme.typography.titleSmall) }
        itemsIndexed(detail.steps.sortedBy { it.order }) { i, step -> Text("${i + 1}. ${MarketText.step(step)}", style = MaterialTheme.typography.bodyMedium) }
        item { HorizontalDivider() }
        if (!listing.mine) item { RateRow(detail, onIntent) }
        item { Text("Reviews", style = MaterialTheme.typography.titleSmall) }
        if (detail.reviews.isEmpty()) item { Text("No reviews yet.", style = MaterialTheme.typography.bodySmall) }
        itemsIndexed(detail.reviews) { _, r ->
            Column {
                Text("★".repeat(r.stars) + "  " + (r.userName ?: ""), style = MaterialTheme.typography.labelMedium)
                r.review?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
    if (reporting) ReportDialog(onDismiss = { reporting = false }) { reason, note ->
        reporting = false
        onIntent(MarketplaceIntent.Report(reason, note))
    }
}

@Composable
private fun RateRow(detail: ListingDetailDto, onIntent: (MarketplaceIntent) -> Unit) {
    var stars by remember(detail.myRating) { mutableIntStateOf(detail.myRating?.stars ?: 0) }
    var review by remember(detail.myRating) { mutableStateOf(detail.myRating?.review.orEmpty()) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(if (detail.myRating != null) "Your rating" else "Rate this flow", style = MaterialTheme.typography.titleSmall)
        Row {
            (1..5).forEach { n ->
                IconButton(onClick = { stars = n }) {
                    Icon(if (n <= stars) Icons.Filled.Star else Icons.Filled.StarBorder, "$n stars", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        OutlinedTextField(
            value = review,
            onValueChange = { review = it.take(500) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("What worked? (optional)") },
        )
        OutlinedButton(onClick = { onIntent(MarketplaceIntent.Rate(stars, review)) }, enabled = stars > 0) {
            Text(if (detail.myRating != null) "Update rating" else "Rate")
        }
    }
}

@Composable
private fun ReportDialog(onDismiss: () -> Unit, onSend: (String, String) -> Unit) {
    var reason by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Report this flow") },
        text = {
            Column {
                Text("Flows reported by several people are hidden until their author fixes them.", style = MaterialTheme.typography.bodySmall)
                MarketText.reportReasons.forEach { (id, label) ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = reason == id, onClick = { reason = id }, role = Role.RadioButton),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = reason == id, onClick = null)
                        Text(label)
                    }
                }
                OutlinedTextField(value = note, onValueChange = { note = it.take(500) }, label = { Text("What happened? (optional)") })
            }
        },
        confirmButton = {
            TextButton(onClick = { reason?.let { onSend(it, note) } }, enabled = reason != null && (reason != "other" || note.trim().length >= 5)) { Text("Send") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
