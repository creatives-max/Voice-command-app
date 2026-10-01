package com.voicecontrol.core.accessibility

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.voicecontrol.core.engine.port.ScreenGateway
import com.voicecontrol.core.model.ActionResult
import com.voicecontrol.core.model.ScreenAction
import com.voicecontrol.core.model.ScreenSnapshot
import com.voicecontrol.core.model.Screenshot
import com.voicecontrol.core.screen.ScreenParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide handle to the running [VoiceControlAccessibilityService].
 *
 * The service attaches itself here; the rest of the app reads screen snapshots and performs
 * actions through this bridge (as a [ScreenGateway]) without holding the service directly.
 */
@Singleton
class AccessibilityBridge @Inject constructor(
    private val parser: ScreenParser,
) : ScreenGateway {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    internal var service: VoiceControlAccessibilityService? = null
        private set

    @Volatile
    private var executor: ActionExecutor? = null

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    override val isAvailable: StateFlow<Boolean> get() = isConnected

    private val _foregroundPackage = MutableStateFlow<String?>(null)
    /** Package of the app currently on screen (never VoiceControl itself or the system UI). */
    val foregroundPackage: StateFlow<String?> = _foregroundPackage.asStateFlow()

    private val _currentSnapshot = MutableStateFlow<ScreenSnapshot?>(null)
    /** Most recent parsed snapshot of the foreground app; refreshed automatically on screen changes. */
    val currentSnapshot: StateFlow<ScreenSnapshot?> = _currentSnapshot.asStateFlow()

    private val _screenChanged = MutableSharedFlow<String>(extraBufferCapacity = 16)
    /** Emits the package name whenever the foreground app's screen changed (debounced). */
    val screenChanged: SharedFlow<String> = _screenChanged.asSharedFlow()

    override val screenChanges: Flow<ScreenSnapshot> =
        _currentSnapshot.filterNotNull().distinctUntilChangedBy { it.packageName + "|" + it.signature }

    private val rawEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
    private var currentActivity: String? = null
    /** VoiceControl's own screens are ignored, except the onboarding practice form (so people can try it safely). */
    @Volatile private var practiceFormOpen = false

    init {
        rawEvents
            .debounce(SCREEN_SETTLE_MS)
            .onEach { refreshSnapshot() }
            .launchIn(scope)
    }

    internal fun attach(service: VoiceControlAccessibilityService) {
        this.service = service
        executor = ActionExecutor(service, parser, ::foregroundRoot)
        _isConnected.value = true
        rawEvents.tryEmit(Unit)
    }

    internal fun detach() {
        service = null
        executor = null
        _isConnected.value = false
        _currentSnapshot.value = null
    }

    internal fun onEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg in ignoredPackages) return
        val isStateChange = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        if (pkg == service?.packageName) {
            val cls = event.className?.toString()
            if (isStateChange && cls != null && looksLikeActivity(cls)) {
                practiceFormOpen = cls == PRACTICE_FORM_ACTIVITY
                if (practiceFormOpen) currentActivity = cls
            }
            if (practiceFormOpen) rawEvents.tryEmit(Unit)
            return
        }
        if (isStateChange) practiceFormOpen = false
        if (isStateChange) {
            val cls = event.className?.toString()
            if (cls != null && looksLikeActivity(cls)) currentActivity = cls
        }
        rawEvents.tryEmit(Unit)
    }

    /** Parses the current foreground app screen now. Returns null when the service is off. */
    suspend fun captureScreen(): ScreenSnapshot? = withContext(Dispatchers.Default) { refreshSnapshot() }

    override suspend fun capture(): ScreenSnapshot? = captureScreen()

    override suspend fun perform(action: ScreenAction): ActionResult {
        val exec = executor ?: return ActionResult.Failure("Accessibility service is off")
        val result = withContext(Dispatchers.Main) { exec.perform(action) }
        // Let the app react, then refresh our view of the screen.
        rawEvents.tryEmit(Unit)
        return result
    }

    override suspend fun screenshot(): Screenshot? = executor?.screenshot()

    /** Root node of the foreground application window (excluding our own overlay and IME windows). */
    internal fun foregroundRoot(): AccessibilityNodeInfo? {
        val svc = service ?: return null
        val ownPackage = svc.packageName
        val appWindows = runCatching { svc.windows }.getOrDefault(emptyList())
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val candidates = appWindows.sortedByDescending { it.isActive || it.isFocused }
        for (window in candidates) {
            val root = window.root ?: continue
            val pkg = root.packageName?.toString()
            if (pkg != null && (pkg != ownPackage || practiceFormOpen) && pkg !in ignoredPackages) return root
        }
        return svc.rootInActiveWindow?.takeIf { root ->
            val pkg = root.packageName?.toString()
            pkg != null && (pkg != ownPackage || practiceFormOpen) && pkg !in ignoredPackages
        }
    }

    private fun refreshSnapshot(): ScreenSnapshot? {
        val root = foregroundRoot() ?: return _currentSnapshot.value
        val pkg = root.packageName?.toString() ?: return null
        if (_foregroundPackage.value != pkg) {
            _foregroundPackage.value = pkg
            service?.let { svc -> svc.notifyForegroundChanged(pkg) }
            if (currentActivity?.startsWith(pkg) == false) currentActivity = null
        }
        val snapshot = runCatching {
            parser.parse(AndroidUiNode(root), pkg, currentActivity, System.currentTimeMillis())
        }.getOrNull() ?: return _currentSnapshot.value
        val previous = _currentSnapshot.value
        _currentSnapshot.value = snapshot
        if (previous?.signature != snapshot.signature || previous.packageName != snapshot.packageName) {
            _screenChanged.tryEmit(pkg)
        }
        return snapshot
    }

    private fun looksLikeActivity(cls: String): Boolean =
        !cls.startsWith("android.widget.") && !cls.startsWith("android.view.") && !cls.startsWith("android.app.Dialog")

    companion object {
        const val SCREEN_SETTLE_MS = 350L
        /** The onboarding practice form inside VoiceControl, which sessions may fill like any other app. */
        const val PRACTICE_FORM_ACTIVITY = "com.voicecontrol.feature.onboarding.PracticeFormActivity"
        val ignoredPackages = setOf(
            "com.android.systemui",
            "com.google.android.inputmethod.latin",
            "com.samsung.android.honeyboard",
            "com.touchtype.swiftkey",
        )
    }
}
