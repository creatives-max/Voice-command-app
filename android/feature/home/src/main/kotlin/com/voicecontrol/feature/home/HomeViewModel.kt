package com.voicecontrol.feature.home

import androidx.lifecycle.viewModelScope
import com.voicecontrol.core.accessibility.AccessibilityBridge
import com.voicecontrol.core.data.auth.AuthRepository
import com.voicecontrol.core.ui.mvi.MviViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    bridge: AccessibilityBridge,
    auth: AuthRepository,
) : MviViewModel<HomeState, HomeIntent, HomeEffect>(HomeState()) {

    init {
        combine(bridge.isConnected, bridge.currentSnapshot, auth.user) { connected, snapshot, user ->
            HomeState(
                serviceConnected = connected,
                lastApp = snapshot?.packageName,
                lastAppFieldCount = snapshot?.fields?.size ?: 0,
                accountEmail = user?.email,
            )
        }.onEach { next -> setState { next } }.launchIn(viewModelScope)
    }

    override suspend fun handleIntent(intent: HomeIntent) {
        when (intent) {
            HomeIntent.OpenAccessibilitySettings -> sendEffect(HomeEffect.LaunchAccessibilitySettings)
        }
    }
}
