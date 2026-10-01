package com.voicecontrol.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voicecontrol.core.accessibility.AccessibilityStatus
import com.voicecontrol.core.ui.components.SectionCard
import com.voicecontrol.core.ui.mvi.CollectEffects

@Composable
fun HomeRoute(
    onOpenInspector: () -> Unit,
    onOpenAccount: (signedIn: Boolean) -> Unit,
    onOpenFlows: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenProfile: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    CollectEffects(viewModel.effects) { effect ->
        when (effect) {
            HomeEffect.LaunchAccessibilitySettings -> context.startActivity(AccessibilityStatus.settingsIntent())
        }
    }
    HomeScreen(state, viewModel::dispatch, onOpenInspector, onOpenAccount, onOpenFlows, onOpenHistory, onOpenSettings, onOpenProfile)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeState,
    onIntent: (HomeIntent) -> Unit,
    onOpenInspector: () -> Unit,
    onOpenAccount: (signedIn: Boolean) -> Unit = {},
    onOpenFlows: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenProfile: () -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("VoiceControl") },
                actions = {
                    IconButton(onClick = onOpenHistory) { Icon(Icons.Filled.History, "History") }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, "Settings") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard(
                title = "Operate any app by voice",
                subtitle = "Hindi · English · Hinglish",
                icon = Icons.Filled.Mic,
            )
            PermissionsCard()
            if (state.serviceConnected) {
                SectionCard(
                    title = "Accessibility service is on",
                    subtitle = (state.lastApp?.let { "Last screen: $it (${state.lastAppFieldCount} fields). " } ?: "") +
                        "Open any app and tap the floating mic to start. Long-press it for the touch panel.",
                    icon = Icons.Filled.CheckCircle,
                )
            } else {
                SectionCard(
                    title = "Turn on VoiceControl",
                    subtitle = "Allow VoiceControl in Accessibility settings so it can read fields and press buttons for you.",
                    icon = Icons.Filled.Accessibility,
                ) {
                    Button(onClick = { onIntent(HomeIntent.OpenAccessibilitySettings) }) { Text("Open accessibility settings") }
                }
            }
            val email = state.accountEmail
            SectionCard(
                title = if (email != null) "Account" else "Sign in to sync",
                subtitle = email?.let { "Signed in as $it. Flows sync to the web dashboard." }
                    ?: "Save flows to your account, edit them on the dashboard and enable AI understanding.",
                icon = Icons.Filled.AccountCircle,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onOpenAccount(email != null) }) { Text(if (email != null) "Profile & account" else "Sign in") }
                    if (email == null) {
                        // Profile values also work offline (local-only mode): they stay on this phone.
                        OutlinedButton(onClick = onOpenProfile) { Text("Edit profile") }
                    }
                }
            }
            SectionCard(
                title = "Saved flows",
                subtitle = "Every form you fill by voice is saved per app and screen. Edited flows run automatically next time.",
                icon = Icons.Filled.Mic,
            ) {
                OutlinedButton(onClick = onOpenFlows) { Text("Open flows") }
            }
            SectionCard(
                title = "Screen inspector",
                subtitle = "See every field and button VoiceControl detects on the last app you opened.",
                icon = Icons.Filled.Search,
            ) {
                OutlinedButton(onClick = onOpenInspector) { Text("Open inspector") }
            }
        }
    }
}
