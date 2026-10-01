package com.voicecontrol.feature.home

data class HomeState(
    val serviceConnected: Boolean = false,
    val lastApp: String? = null,
    val lastAppFieldCount: Int = 0,
    val accountEmail: String? = null,
)

sealed interface HomeIntent {
    data object OpenAccessibilitySettings : HomeIntent
}

sealed interface HomeEffect {
    data object LaunchAccessibilitySettings : HomeEffect
}
