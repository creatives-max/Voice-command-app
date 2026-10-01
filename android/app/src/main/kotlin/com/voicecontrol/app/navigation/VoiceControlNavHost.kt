package com.voicecontrol.app.navigation

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import android.widget.Toast
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.voicecontrol.feature.auth.AuthRoute
import com.voicecontrol.feature.auth.ProfileRoute
import com.voicecontrol.feature.care.CareRoute
import com.voicecontrol.feature.flows.FlowsRoute
import com.voicecontrol.feature.flows.TeachReviewRoute
import com.voicecontrol.feature.history.HistoryRoute
import com.voicecontrol.feature.home.HomeRoute
import com.voicecontrol.feature.inspector.InspectorRoute
import com.voicecontrol.feature.onboarding.OnboardingRoute
import com.voicecontrol.feature.settings.PrivacyScreen
import com.voicecontrol.feature.settings.OfflineLanguagesRoute
import com.voicecontrol.feature.settings.SettingsRoute
import kotlinx.serialization.Serializable

@Serializable data object HomeDestination
@Serializable data object InspectorDestination
@Serializable data object AuthDestination
@Serializable data object ProfileDestination
@Serializable data object FlowsDestination
@Serializable data object HistoryDestination
@Serializable data object SettingsDestination
@Serializable data object PrivacyDestination
@Serializable data object OnboardingDestination
@Serializable data object TeachReviewDestination
@Serializable data object CareDestination
@Serializable data object OfflineLanguagesDestination

/** Top-level places shown in the navigation rail on tablets and unfolded phones. */
private class RailItem(val label: String, val icon: ImageVector, val isCurrent: (NavDestination?) -> Boolean, val go: () -> Any)

/**
 * App navigation. On wide windows ([wide]) a navigation rail with the main places sits beside the content;
 * on phones the screens link to each other as before.
 */
@Composable
fun VoiceControlNavHost(
    startWithOnboarding: Boolean = false,
    wide: Boolean = false,
    signedIn: Boolean = false,
    reviewTeaching: Boolean = false,
    onReviewOpened: () -> Unit = {},
) {
    val navController = rememberNavController()
    // A finished "teach by doing" recording opens its review screen.
    LaunchedEffect(reviewTeaching) {
        if (reviewTeaching) {
            navController.navigate(TeachReviewDestination) { launchSingleTop = true }
            onReviewOpened()
        }
    }
    val entry by navController.currentBackStackEntryAsState()
    val destination = entry?.destination
    val showRail = wide && destination?.hasRoute(OnboardingDestination::class) != true
    if (showRail) {
        val items = listOf(
            RailItem("Home", Icons.Filled.Home, { it?.hasRoute(HomeDestination::class) == true }) { HomeDestination },
            RailItem("Flows", Icons.Filled.ViewList, { it?.hasRoute(FlowsDestination::class) == true }) { FlowsDestination },
            RailItem("History", Icons.Filled.History, { it?.hasRoute(HistoryDestination::class) == true }) { HistoryDestination },
            RailItem(
                "Account",
                Icons.Filled.AccountCircle,
                { it?.hasRoute(ProfileDestination::class) == true || it?.hasRoute(AuthDestination::class) == true },
            ) { if (signedIn) ProfileDestination else AuthDestination },
            RailItem("Settings", Icons.Filled.Settings, { it?.hasRoute(SettingsDestination::class) == true || it?.hasRoute(PrivacyDestination::class) == true }) {
                SettingsDestination
            },
        )
        Row(Modifier.fillMaxSize()) {
            NavigationRail(Modifier.fillMaxHeight().testTag("navigation-rail")) {
                items.forEach { item ->
                    NavigationRailItem(
                        selected = item.isCurrent(destination),
                        onClick = { navController.navigateTopLevel(item.go()) },
                        icon = { Icon(item.icon, contentDescription = null) },
                        label = { Text(item.label) },
                    )
                }
            }
            Graph(navController, startWithOnboarding, Modifier.fillMaxSize())
        }
    } else {
        Graph(navController, startWithOnboarding, Modifier.fillMaxSize())
    }
}

/** Switches top-level place keeping one copy of each on the back stack. */
private fun NavHostController.navigateTopLevel(route: Any) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun Graph(navController: NavHostController, startWithOnboarding: Boolean, modifier: Modifier) {
    NavHost(navController = navController, startDestination = if (startWithOnboarding) OnboardingDestination else HomeDestination, modifier = modifier) {
        composable<OnboardingDestination> {
            OnboardingRoute(onFinished = {
                if (!navController.popBackStack()) {
                    navController.navigate(HomeDestination) { popUpTo(OnboardingDestination) { inclusive = true } }
                }
            })
        }
        composable<HomeDestination> {
            HomeRoute(
                onOpenInspector = { navController.navigate(InspectorDestination) },
                onOpenAccount = { signedIn -> navController.navigate(if (signedIn) ProfileDestination else AuthDestination) },
                onOpenFlows = { navController.navigate(FlowsDestination) },
                onOpenHistory = { navController.navigate(HistoryDestination) },
                onOpenSettings = { navController.navigate(SettingsDestination) },
                onOpenProfile = { navController.navigate(ProfileDestination) },
                onOpenTutorial = { navController.navigate(OnboardingDestination) },
            )
        }
        composable<TeachReviewDestination> {
            val context = LocalContext.current
            TeachReviewRoute(onDone = { message ->
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                navController.navigate(FlowsDestination) { popUpTo(TeachReviewDestination) { inclusive = true } }
            })
        }
        composable<HistoryDestination> {
            HistoryRoute(onBack = { navController.popBackStack() })
        }
        composable<SettingsDestination> {
            SettingsRoute(
                onBack = { navController.popBackStack() },
                onOpenPrivacy = { navController.navigate(PrivacyDestination) },
                onOpenCare = { navController.navigate(CareDestination) },
                onOpenOfflineLanguages = { navController.navigate(OfflineLanguagesDestination) },
            )
        }
        composable<OfflineLanguagesDestination> {
            OfflineLanguagesRoute(onBack = { navController.popBackStack() })
        }
        composable<CareDestination> {
            CareRoute(onBack = { navController.popBackStack() }, onSignIn = { navController.navigate(AuthDestination) })
        }
        composable<PrivacyDestination> {
            PrivacyScreen(onBack = { navController.popBackStack() })
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
