package com.voicecontrol.core.accessibility

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.voicecontrol.core.engine.port.InteractionKind
import com.voicecontrol.core.engine.port.InteractionSource
import com.voicecontrol.core.engine.port.ScreenGateway
import com.voicecontrol.core.engine.port.UserInteraction
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
import kotlinx.coroutines.launch
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
) : ScreenGateway, InteractionSource {
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

    private val _interactions = MutableSharedFlow<UserInteraction>(extraBufferCapacity = 32)
    /** Typing and presses in the foreground app; only worked out while someone listens (teach-by-doing). */
    override val interactions: Flow<UserInteraction> = _interactions
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

    /** A notification seen on this phone (kept in memory only, for "read my messages"). */
    data class SeenNotification(val appPackage: String, val app: String, val title: String?, val text: String, val atMillis: Long)

    private val notifications = ArrayDeque<SeenNotification>()

    /** Home, recents, notifications, lock, screenshot…: an AccessibilityService global action. False when the service is off. */
    fun performGlobal(action: Int): Boolean = service?.performGlobalAction(action) ?: false

    /** Notifications from the last [maxAgeMillis], newest first. */
    fun recentNotifications(maxAgeMillis: Long = NOTIFICATION_MAX_AGE_MS): List<SeenNotification> {
        val since = System.currentTimeMillis() - maxAgeMillis
        return synchronized(notifications) { notifications.filter { it.atMillis >= since }.reversed() }
    }

    private fun recordNotification(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        val svc = service ?: return
        if (pkg == svc.packageName || pkg in ignoredPackages || pkg == "android") return
        val n = event.parcelableData as? android.app.Notification
        val skipFlags = android.app.Notification.FLAG_ONGOING_EVENT or android.app.Notification.FLAG_GROUP_SUMMARY or
            android.app.Notification.FLAG_FOREGROUND_SERVICE
        if (n != null && (n.flags and skipFlags) != 0) return
        val extras = n?.extras
        val title = extras?.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()?.takeIf { it.isNotBlank() }
        val text = (extras?.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT) ?: extras?.getCharSequence(android.app.Notification.EXTRA_TEXT))
            ?.toString()?.takeIf { it.isNotBlank() }
            ?: event.text.joinToString(" ").takeIf { it.isNotBlank() }
            ?: return
        val pm = svc.packageManager
        val app = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        synchronized(notifications) {
            notifications.removeAll { it.appPackage == pkg && it.title == title && it.text == text }
            notifications.addLast(SeenNotification(pkg, app, title, text.take(NOTIFICATION_MAX_CHARS), System.currentTimeMillis()))
            while (notifications.size > NOTIFICATION_MAX_COUNT) notifications.removeFirst()
        }
    }

    internal fun onEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            recordNotification(event)
            return
        }
        val pkg = event.packageName?.toString() ?: return
        if (pkg in ignoredPackages) return
        val isStateChange = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        if (pkg == service?.packageName) {
            val cls = event.className?.toString()
            if (isStateChange && cls != null && looksLikeActivity(cls)) {
                practiceFormOpen = cls == PRACTICE_FORM_ACTIVITY
                if (practiceFormOpen) currentActivity = cls
            }
            if (practiceFormOpen) {
                reportInteraction(event)
                rawEvents.tryEmit(Unit)
            }
            return
        }
        if (isStateChange) practiceFormOpen = false
        reportInteraction(event)
        if (isStateChange) {
            val cls = event.className?.toString()
            if (cls != null && looksLikeActivity(cls)) currentActivity = cls
        }
        rawEvents.tryEmit(Unit)
    }

    private fun reportInteraction(event: AccessibilityEvent) {
        val kind = when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> InteractionKind.TYPED
            AccessibilityEvent.TYPE_VIEW_CLICKED -> InteractionKind.PRESSED
            else -> return
        }
        if (_interactions.subscriptionCount.value == 0) return
        val source = event.source ?: return
        val rect = android.graphics.Rect().also(source::getBoundsInScreen)
        val bounds = com.voicecontrol.core.model.Bounds(rect.left, rect.top, rect.right, rect.bottom)
        val viewId = source.viewIdResourceName
        // The screen as it was when the user touched it: a tap that opens another screen belongs here.
        val before = _currentSnapshot.value?.takeIf { it.packageName == event.packageName?.toString() }
        scope.launch {
            val onBefore = before?.let { exactElement(it, bounds, viewId) }
            if (onBefore != null) {
                _interactions.emit(UserInteraction(kind, onBefore.id, before))
                return@launch
            }
            val snapshot = refreshSnapshot() ?: return@launch
            val element = elementAt(snapshot, bounds, viewId) ?: return@launch
            _interactions.emit(UserInteraction(kind, element.id, snapshot))
        }
    }

    /** The element with exactly the event's bounds (or view id and position), or null when the screen moved. */
    private fun exactElement(snapshot: ScreenSnapshot, bounds: com.voicecontrol.core.model.Bounds, viewId: String?): com.voicecontrol.core.model.ScreenElement? =
        snapshot.elements.firstOrNull { it.bounds == bounds && (viewId == null || it.viewId == null || it.viewId == viewId) }
            ?: viewId?.let { id ->
                snapshot.elements.singleOrNull { it.viewId == id }
                    ?.takeIf { e -> bounds.centerX in e.bounds.left..e.bounds.right && bounds.centerY in e.bounds.top..e.bounds.bottom }
            }

    /** The element an event came from: same view id, else the smallest element containing the event's center. */
    private fun elementAt(snapshot: ScreenSnapshot, bounds: com.voicecontrol.core.model.Bounds, viewId: String?): com.voicecontrol.core.model.ScreenElement? {
        if (viewId != null) snapshot.elements.firstOrNull { it.viewId == viewId && it.bounds == bounds }?.let { return it }
        val cx = bounds.centerX
        val cy = bounds.centerY
        return snapshot.elements
            .filter { e -> !e.bounds.isEmpty && cx in e.bounds.left..e.bounds.right && cy in e.bounds.top..e.bounds.bottom }
            .minByOrNull { it.bounds.width.toLong() * it.bounds.height }
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
        const val NOTIFICATION_MAX_COUNT = 30
        const val NOTIFICATION_MAX_CHARS = 500
        const val NOTIFICATION_MAX_AGE_MS = 12 * 60 * 60 * 1000L
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
