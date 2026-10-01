package com.voicecontrol.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.voicecontrol.core.data.auth.AuthRepository
import com.voicecontrol.core.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** App-wide state for the shell: first-run tutorial, sign-in (for the Account item) and the secure-window flag. */
@HiltViewModel
class AppViewModel @Inject constructor(settings: SettingsRepository, auth: AuthRepository) : ViewModel() {
    /** null until settings were read once. */
    val onboardingDone: StateFlow<Boolean?> = settings.settings.map { it.onboardingDone }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val appLock: StateFlow<Boolean> = settings.settings.map { it.appLock }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val themeMode: StateFlow<com.voicecontrol.core.model.ThemeMode> =
        settings.settings.map { it.themeMode }.stateIn(viewModelScope, SharingStarted.Eagerly, com.voicecontrol.core.model.ThemeMode.SYSTEM)
    val signedIn: StateFlow<Boolean> = auth.user.map { it != null }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
}
