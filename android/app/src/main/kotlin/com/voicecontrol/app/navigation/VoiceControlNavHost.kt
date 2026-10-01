package com.voicecontrol.app.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.voicecontrol.feature.auth.AuthRoute
import com.voicecontrol.feature.auth.ProfileRoute
import com.voicecontrol.feature.flows.FlowsRoute
import com.voicecontrol.feature.home.HomeRoute
import com.voicecontrol.feature.inspector.InspectorRoute
import kotlinx.serialization.Serializable

@Serializable data object HomeDestination
@Serializable data object InspectorDestination
@Serializable data object AuthDestination
@Serializable data object ProfileDestination
@Serializable data object FlowsDestination

@Composable
fun VoiceControlNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = HomeDestination) {
        composable<HomeDestination> {
            HomeRoute(
                onOpenInspector = { navController.navigate(InspectorDestination) },
                onOpenAccount = { signedIn -> navController.navigate(if (signedIn) ProfileDestination else AuthDestination) },
                onOpenFlows = { navController.navigate(FlowsDestination) },
            )
        }
        composable<FlowsDestination> {
            FlowsRoute(onBack = { navController.popBackStack() })
        }
        composable<InspectorDestination> {
            InspectorRoute(onBack = { navController.popBackStack() })
        }
        composable<AuthDestination> {
            AuthRoute(
                onBack = { navController.popBackStack() },
                onSignedIn = {
                    navController.navigate(ProfileDestination) { popUpTo(HomeDestination) }
                },
            )
        }
        composable<ProfileDestination> {
            ProfileRoute(
                onBack = { navController.popBackStack() },
                onSignedOut = { navController.popBackStack(HomeDestination, inclusive = false) },
            )
        }
    }
}
