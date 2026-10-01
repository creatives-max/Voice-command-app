package com.voicecontrol.app.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.voicecontrol.feature.home.HomeRoute
import com.voicecontrol.feature.inspector.InspectorRoute
import kotlinx.serialization.Serializable

@Serializable data object HomeDestination
@Serializable data object InspectorDestination

@Composable
fun VoiceControlNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = HomeDestination) {
        composable<HomeDestination> {
            HomeRoute(onOpenInspector = { navController.navigate(InspectorDestination) })
        }
        composable<InspectorDestination> {
            InspectorRoute(onBack = { navController.popBackStack() })
        }
    }
}
