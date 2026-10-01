package com.voicecontrol.app.lock

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.voicecontrol.core.common.lock.AppLockPolicy
import com.voicecontrol.core.data.settings.SettingsRepository
import com.voicecontrol.core.ui.security.Biometrics
import com.voicecontrol.core.ui.security.findFragmentActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks whether VoiceControl's screens must be hidden behind the lock: when the app lock is on, at
 * start and after the app spent [com.voicecontrol.core.data.settings.AppSettings.lockTimeoutSeconds]
 * in the background.
 */
@Singleton
class AppLockController @Inject constructor(private val settings: SettingsRepository) {
    /** null until the settings were read once (nothing is shown yet), then whether the lock is up. */
    private val _locked = MutableStateFlow<Boolean?>(null)
    val locked: StateFlow<Boolean?> = _locked.asStateFlow()

    private var unlockedOnce = false
    private var backgroundSince: Long? = null

    /** Called when the activity becomes visible. */
    suspend fun onForeground(now: Long = System.currentTimeMillis()) {
        val s = settings.appSettings()
        _locked.value = AppLockPolicy.shouldLock(s.appLock, unlockedOnce, backgroundSince, now, s.lockTimeoutSeconds)
        backgroundSince = null
    }

    fun onBackground(now: Long = System.currentTimeMillis()) {
        if (_locked.value == false) backgroundSince = now
    }

    fun unlocked() {
        unlockedOnce = true
        backgroundSince = null
        _locked.value = false
    }
}

/** Full-screen cover shown while locked; asks for fingerprint, face or the screen lock right away. */
@Composable
fun LockScreen(onUnlocked: () -> Unit) {
    val context = LocalContext.current
    var message by remember { mutableStateOf<String?>(null) }
    val ask = {
        val activity = context.findFragmentActivity()
        if (activity == null || !Biometrics.available(context)) {
            // The screen lock was removed after the app lock was turned on: nothing to check against.
            onUnlocked()
        } else {
            Biometrics.authenticate(activity, "Unlock VoiceControl", null) { ok, error -> if (ok) onUnlocked() else message = error }
        }
    }
    LaunchedEffect(Unit) { ask() }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).semantics { contentDescription = "VoiceControl is locked" }, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(32.dp)) {
            Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
            Text("VoiceControl is locked", style = MaterialTheme.typography.headlineSmall)
            Text(
                message ?: "Use your fingerprint, face or screen lock to open it.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = { ask() }) { Text("Unlock") }
        }
    }
}
