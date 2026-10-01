package com.voicecontrol.app

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.voicecontrol.app.lock.AppLockController
import com.voicecontrol.app.lock.LockScreen
import com.voicecontrol.app.navigation.VoiceControlNavHost
import com.voicecontrol.core.ui.theme.VoiceControlTheme
import androidx.compose.foundation.isSystemInDarkTheme
import com.voicecontrol.feature.assistant.overlay.OverlayManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** FragmentActivity so the biometric prompt can be shown for the app lock. */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var lock: AppLockController

    private val appViewModel: AppViewModel by viewModels()

    /** Set when the overlay opened the app to review a "teach by doing" recording. */
    private val reviewTeaching = MutableStateFlow(false)

    /** Set when a launcher shortcut opened the app. */
    private val shortcut = MutableStateFlow<AppShortcut?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == OverlayManager.ACTION_REVIEW_TEACHING) reviewTeaching.value = true
        AppShortcut.fromAction(intent.action)?.let { shortcut.value = it }
    }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null && intent?.action == OverlayManager.ACTION_REVIEW_TEACHING) reviewTeaching.value = true
        if (savedInstanceState == null) shortcut.value = AppShortcut.fromAction(intent?.action)
        AppShortcut.publish(this)
        // With the app lock on, keep VoiceControl's screens out of screenshots and the recent-apps preview.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                appViewModel.appLock.collect { secure ->
                    if (secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        }
        setContent {
            val themeMode by appViewModel.themeMode.collectAsStateWithLifecycle()
            VoiceControlTheme(darkTheme = themeMode.isDark(isSystemInDarkTheme())) {
                val locked by lock.locked.collectAsStateWithLifecycle()
                val onboardingDone by appViewModel.onboardingDone.collectAsStateWithLifecycle()
                val signedIn by appViewModel.signedIn.collectAsStateWithLifecycle()
                val review by reviewTeaching.collectAsStateWithLifecycle()
                val openShortcut by shortcut.collectAsStateWithLifecycle()
                val wide = calculateWindowSizeClass(this).widthSizeClass != WindowWidthSizeClass.Compact
                // Decided once per launch, so finishing the tutorial doesn't rebuild the navigation graph.
                var startWithOnboarding by remember { mutableStateOf<Boolean?>(null) }
                onboardingDone?.let { done -> if (startWithOnboarding == null) startWithOnboarding = !done }
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    val start = startWithOnboarding
                    // Content stays composed under the lock (navigation is kept) but is hidden from everyone.
                    if (start != null && locked != null) {
                        val hidden = if (locked == true) Modifier.clearAndSetSemantics { } else Modifier
                        Box(hidden) { VoiceControlNavHost(
                                startWithOnboarding = start,
                                wide = wide,
                                signedIn = signedIn,
                                reviewTeaching = review && start == false,
                                onReviewOpened = { reviewTeaching.value = false },
                                shortcut = openShortcut.takeIf { start == false && locked == false },
                                onShortcutOpened = { shortcut.value = null },
                            ) }
                    }
                    if (locked == true) LockScreen(onUnlocked = lock::unlocked)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch { lock.onForeground() }
    }

    override fun onStop() {
        lock.onBackground()
        super.onStop()
    }
}
