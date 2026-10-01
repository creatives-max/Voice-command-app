package com.voicecontrol.app.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.voicecontrol.feature.home.HomeRoute
import com.voicecontrol.feature.home.HomeScreen
import kotlinx.serialization.Serializable

@Composable
fun VoiceControlNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = HomeDestination) {
        composable<HomeDestination> { HomeRoute() }
    }
}

@Serializable
data object HomeDestination
