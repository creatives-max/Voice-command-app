package com.voicecontrol.feature.care

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VolunteerActivism
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voicecontrol.core.data.care.CareUnavailable
import com.voicecontrol.core.network.dto.CareLinkDto
import com.voicecontrol.core.ui.components.EmptyState
import com.voicecontrol.core.ui.components.LoadingBox
import com.voicecontrol.core.ui.mvi.CollectEffects

@Composable
fun CareRoute(onBack: () -> Unit, onSignIn: () -> Unit, viewModel: CareViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    CollectEffects(viewModel.effects) { effect ->
        when (effect) {
            is CareEffect.Message -> snackbar.showSnackbar(effect.text)
            is CareEffect.Share -> runCatching {
                context.startActivity(
                    Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, effect.text), "Share the code")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }
    CareScreen(state, snackbar, onBack, onSignIn, viewModel::dispatch)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CareScreen(state: CareState, snackbar: SnackbarHostState, onBack: () -> Unit, onSignIn: () -> Unit, onIntent: (CareIntent) -> Unit) {
    var confirmEnd by remember { mutableStateOf<CareLinkDto?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Caregivers") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when {
            state.loading -> LoadingBox(Modifier.padding(padding))
            state.unavailable != null -> Column(Modifier.padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                EmptyState(
                    if (state.unavailable == CareUnavailable.ON_DEVICE_ONLY) {
                        "Caregivers work through your VoiceControl account. Turn off “On-device only” in Settings to use them."
                    } else {
                        "Sign in so someone you trust can set up flows for you from their computer, with your permission."
                    },
                    Modifier.fillMaxWidth(),
                    Icons.Filled.VolunteerActivism,
                )
                if (state.unavailable == CareUnavailable.SIGNED_OUT) Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) { Text("Sign in") }
            }
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text(
                        "A caregiver — a son, daughter or friend — can set up and fix your flows from the VoiceControl website. " +
                            "They only get what you allow here, they never see your passwords or what you type, and you can stop it any time.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
                item { InviteCard(state, onIntent) }
                if (state.helpers.isNotEmpty()) item { Text("People who help you", style = MaterialTheme.typography.titleMedium) }
                items(state.helpers, key = { it.id }) { link ->
                    HelperCard(link, state.events[link.id], state.myEmail, onIntent, onEnd = { confirmEnd = link })
                }
                if (state.helping.isNotEmpty()) {
                    item { Text("People you help", style = MaterialTheme.typography.titleMedium) }
                    items(state.helping, key = { it.id }) { link ->
                        Card(Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(CareText.name(link), style = MaterialTheme.typography.titleSmall)
                                    Text("Set up their flows on the VoiceControl website.", style = MaterialTheme.typography.bodySmall)
                                }
                                TextButton(onClick = { confirmEnd = link }) { Text("Stop") }
                            }
                        }
                    }
                }
            }
        }
    }
    confirmEnd?.let { link ->
        AlertDialog(
            onDismissRequest = { confirmEnd = null },
            title = { Text(if (link.role == "receiver") "Stop ${CareText.name(link)} helping you?" else "Stop helping ${CareText.name(link)}?") },
            text = { Text("This takes effect at once. You can make a new invite later.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmEnd = null
                    onIntent(CareIntent.End(link.id))
                }) { Text("Stop") }
            },
            dismissButton = { TextButton(onClick = { confirmEnd = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun InviteCard(state: CareState, onIntent: (CareIntent) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Invite a helper", style = MaterialTheme.typography.titleMedium)
            Text("They can always see your flows. Also let them:", style = MaterialTheme.typography.bodySmall)
            CareText.permissions.forEach { p ->
                PermissionRow(CareText.permission(p), p in state.chosen) { on -> onIntent(CareIntent.ChoosePermission(p, on)) }
            }
            Button(onClick = { onIntent(CareIntent.CreateInvite) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.PersonAdd, null)
                Spacer(Modifier.width(8.dp))
                Text("Create a code")
            }
            state.invite?.let { invite ->
                Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Read this code to your helper. It works once, for ${CareText.minutesLeft(invite.expiresAt)} minutes:")
                    Text(
                        invite.code,
                        style = MaterialTheme.typography.displaySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.semantics { contentDescription = "Code ${CareText.spoken(invite.code)}" },
                    )
                    OutlinedButton(onClick = { onIntent(CareIntent.ShareInvite) }) {
                        Icon(Icons.Filled.Share, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Share")
                    }
                }
            }
            state.pending.filter { it.id != state.invite?.link?.id }.forEach { link ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Open invite · ${CareText.minutesLeft(link.expiresAt)} min left", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { onIntent(CareIntent.Cancel(link.id)) }) { Text("Cancel") }
                }
            }
        }
    }
}

@Composable
private fun HelperCard(link: CareLinkDto, events: List<com.voicecontrol.core.network.dto.CareEventDto>?, myEmail: String?, onIntent: (CareIntent) -> Unit, onEnd: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(CareText.name(link), style = MaterialTheme.typography.titleMedium)
            CareText.permissions.forEach { p ->
                PermissionRow(CareText.permission(p), p in link.permissions) { on -> onIntent(CareIntent.SetPermission(link.id, p, on)) }
            }
            Row {
                TextButton(onClick = { onIntent(CareIntent.ToggleActivity(link.id)) }) {
                    Icon(Icons.Filled.History, null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (events == null) "What they did" else "Hide activity")
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onEnd) { Text("Stop their help") }
            }
            events?.let { list ->
                if (list.isEmpty()) Text("Nothing yet.", style = MaterialTheme.typography.bodySmall)
                list.forEach { e ->
                    Text("${e.at.take(16).replace('T', ' ')} — ${CareText.describe(e, myEmail)}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.semantics { contentDescription = label })
    }
}
