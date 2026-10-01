package com.voicecontrol.feature.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.voicecontrol.core.accessibility.AccessibilityStatus
import com.voicecontrol.core.data.settings.AppSettings
import com.voicecontrol.core.data.settings.SettingsRepository
import com.voicecontrol.core.model.Language
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The tutorial pages, in order. */
enum class OnboardingPage { WELCOME, MICROPHONE, SERVICE, LANGUAGE, PRIVACY, PRACTICE }

@HiltViewModel
class OnboardingViewModel @Inject constructor(private val settings: SettingsRepository) : ViewModel() {
    val state: StateFlow<AppSettings> = settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    fun update(change: (AppSettings) -> AppSettings) {
        viewModelScope.launch { settings.update(change) }
    }

    fun finish(onDone: () -> Unit) {
        viewModelScope.launch {
            settings.update { it.copy(onboardingDone = true) }
            onDone()
        }
    }
}

private fun Context.granted(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/** First-run tutorial: what VoiceControl does, permissions, language, privacy choices and a practice form. */
@Composable
fun OnboardingRoute(onFinished: () -> Unit, viewModel: OnboardingViewModel = hiltViewModel()) {
    val settings by viewModel.state.collectAsStateWithLifecycle()
    OnboardingScreen(settings, viewModel::update, finish = { viewModel.finish(onFinished) })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(settings: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit, finish: () -> Unit) {
    val pages = OnboardingPage.entries
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var micGranted by remember { mutableStateOf(context.granted(Manifest.permission.RECORD_AUDIO)) }
    var serviceOn by remember { mutableStateOf(AccessibilityStatus.isEnabledInSettings(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        micGranted = context.granted(Manifest.permission.RECORD_AUDIO)
        serviceOn = AccessibilityStatus.isEnabledInSettings(context)
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        micGranted = context.granted(Manifest.permission.RECORD_AUDIO)
    }
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                LinearProgressIndicator(progress = { (pager.currentPage + 1f) / pages.size }, modifier = Modifier.weight(1f).padding(8.dp))
                TextButton(onClick = finish, modifier = Modifier.testTag("onboarding-skip")) { Text("Skip") }
            }
            HorizontalPager(pager, Modifier.weight(1f)) { index ->
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                ) {
                    when (pages[index]) {
                        OnboardingPage.WELCOME -> Page(
                            Icons.Filled.Mic,
                            "Fill any form by voice",
                            "VoiceControl reads the fields of the app in front of you, asks for each one and types your answers. " +
                                "It works in Hindi, English, Hinglish and more Indian languages.",
                        )
                        OnboardingPage.MICROPHONE -> Page(Icons.Filled.Mic, "Allow the microphone", "Needed to hear your answers. Audio is only used while a session runs and is never recorded.") {
                            if (micGranted) {
                                Done("Microphone allowed")
                            } else {
                                Button(onClick = {
                                    permissions.launch(
                                        buildList {
                                            add(Manifest.permission.RECORD_AUDIO)
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
                                        }.toTypedArray(),
                                    )
                                }) { Text("Allow microphone") }
                            }
                        }
                        OnboardingPage.SERVICE -> Page(
                            Icons.Filled.Accessibility,
                            "Turn on VoiceControl",
                            "In Accessibility settings, choose VoiceControl and turn it on. It lets VoiceControl read fields and press buttons for you. " +
                                "Password, OTP and PIN fields are never read.",
                        ) {
                            if (serviceOn) Done("VoiceControl is on") else Button(onClick = { context.startActivity(AccessibilityStatus.settingsIntent()) }) { Text("Open accessibility settings") }
                        }
                        OnboardingPage.LANGUAGE -> Page(Icons.Filled.Language, "Your language", "VoiceControl asks questions and understands answers in this language. Change it any time in Settings.") {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Language.entries.forEach { lang ->
                                    FilterChip(selected = settings.language == lang, onClick = { update { it.copy(language = lang) } }, label = { Text(lang.nativeName) })
                                }
                            }
                        }
                        OnboardingPage.PRIVACY -> Page(Icons.Filled.Shield, "Your privacy", "Choose what may leave this phone. You can change these later in Settings.") {
                            Toggle("On-device only", "Nothing is sent to the server: no sign-in, sync or AI help.", settings.localOnly) { on ->
                                update { it.copy(localOnly = on, visionFallback = if (on) false else it.visionFallback) }
                            }
                            Toggle("Send crash reports", "If VoiceControl crashes, send what went wrong without numbers, emails or answers.", settings.crashReports) { on ->
                                update { it.copy(crashReports = on) }
                            }
                        }
                        OnboardingPage.PRACTICE -> Page(
                            Icons.Filled.EditNote,
                            "Try it",
                            "Open the practice form, tap the floating mic and answer the questions. Say “next”, “repeat” or “submit” at any time.",
                        ) {
                            OutlinedButton(onClick = { context.startActivity(Intent(context, PracticeFormActivity::class.java)) }) { Text("Open practice form") }
                            Button(onClick = finish, modifier = Modifier.testTag("onboarding-finish")) { Text("Start using VoiceControl") }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }, enabled = pager.currentPage > 0) { Text("Back") }
                if (pager.currentPage < pages.lastIndex) {
                    Button(
                        onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                        modifier = Modifier.testTag("onboarding-next"),
                    ) { Text("Next") }
                } else {
                    Spacer(Modifier.size(1.dp))
                }
            }
        }
    }
}

@Composable
private fun Page(icon: ImageVector, title: String, body: String, content: @Composable () -> Unit = {}) {
    Column(Modifier.widthIn(max = 560.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(icon, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

@Composable
private fun Done(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun Toggle(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
