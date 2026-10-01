package com.voicecontrol.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
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
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    CollectEffects(viewModel.effects) { effect ->
        when (effect) {
            HomeEffect.LaunchAccessibilitySettings -> context.startActivity(AccessibilityStatus.settingsIntent())
        }
    }
    HomeScreen(state, viewModel::dispatch, onOpenInspector)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeState,
    onIntent: (HomeIntent) -> Unit,
    onOpenInspector: () -> Unit,
) {
    Scaffold(topBar = { TopAppBar(title = { Text("VoiceControl") }) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard(
                title = "Operate any app by voice",
                subtitle = "Hindi · English · Hinglish",
                icon = Icons.Filled.Mic,
            )
            if (state.serviceConnected) {
                SectionCard(
                    title = "Accessibility service is on",
                    subtitle = state.lastApp?.let { "Last screen: $it (${state.lastAppFieldCount} fields)" }
                        ?: "Open any app to start",
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
