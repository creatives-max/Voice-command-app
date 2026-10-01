package com.voicecontrol.feature.assistant.remote

import android.accessibilityservice.AccessibilityService
import com.voicecontrol.core.accessibility.ServiceListener
import com.voicecontrol.core.data.automation.RemoteRunRepository
import com.voicecontrol.core.engine.AssistantEngine
import com.voicecontrol.core.engine.EngineEvent
import com.voicecontrol.core.engine.port.FlowLauncher
import com.voicecontrol.core.engine.port.LaunchResult
import com.voicecontrol.core.model.FlowDefinition
import com.voicecontrol.core.network.dto.RunRequestDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs flows on this phone without a tap, while the accessibility service is on:
 * - long-polls the backend for "Run now" and scheduled runs (and for stop requests),
 * - starts a flow when its app opens if it has an app-open trigger,
 * - logs runs started by voice shortcuts (voice macros) on the dashboard,
 * - streams each run's value-free progress (labels and outcomes, never values) to the dashboard log.
 */
@Singleton
class RemoteRunCoordinator @Inject constructor(
    private val repository: RemoteRunRepository,
    private val launcher: FlowLauncher,
    private val engine: AssistantEngine,
    private val shortcuts: com.voicecontrol.core.data.shortcuts.VoiceShortcutRepository,
) : ServiceListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var appOpenJob: Job? = null

    /** The run request this phone is executing, if any. */
    @Volatile
    private var currentRequest: String? = null
    private var lastAppOpen: Pair<String, Long>? = null

    override fun onServiceConnected(service: AccessibilityService) {
        scope.launch { pollLoop() }
        scope.launch {
            shortcuts.started.collect { shortcut ->
                // The session is already running: log the rest of it as a voice run of an account flow.
                if (currentRequest != null || shortcut.flowId.startsWith(FlowDefinition.LOCAL_PREFIX) || !repository.online()) return@collect
                val buffer = subscribe()
                val requestId = runCatching { repository.reportVoiceRun(shortcut.flowId, shortcut.triggerId) }.getOrNull()
                if (requestId == null) buffer.second.cancel() else stream(requestId, buffer)
            }
        }
        scope.launch {
            while (isActive) {
                runCatching { repository.refreshTriggers() }
                delay(TRIGGER_REFRESH_MS)
            }
        }
    }

    override fun onServiceDisconnected() {
        scope.coroutineContext.cancelChildren()
        currentRequest = null
    }

    override fun onForegroundAppChanged(packageName: String) {
        val trigger = repository.triggerFor(packageName) ?: return
        val now = System.currentTimeMillis()
        val last = lastAppOpen
        if (last != null && last.first == packageName && now - last.second < APP_OPEN_COOLDOWN_MS) return
        appOpenJob?.cancel()
        appOpenJob = scope.launch {
            delay(APP_OPEN_SETTLE_MS)
            if (engine.isActive || currentRequest != null) return@launch
            val flow = repository.loadFlow(trigger.flowId) ?: return@launch
            lastAppOpen = packageName to System.currentTimeMillis()
            val requestId = if (repository.online()) runCatching { repository.reportAppOpen(trigger) }.getOrNull() else null
            if (requestId == null) {
                launcher.launch(flow)
            } else {
                follow(requestId, flow)
            }
        }
    }

    private suspend fun pollLoop() {
        var registered = false
        var backoff = MIN_BACKOFF_MS
        while (scope.isActive) {
            if (!repository.remoteEnabled()) {
                registered = false
                delay(DISABLED_RECHECK_MS)
                continue
            }
            try {
                if (!registered) {
                    repository.register()
                    registered = true
                }
                val commands = repository.poll(POLL_WAIT_SECONDS)
                commands.cancel.forEach { id ->
                    if (id == currentRequest) engine.stop() else report(id, "CANCELLED", "status" to "Stopped before it started")
                }
                commands.run.forEach { handleRun(it) }
                backoff = MIN_BACKOFF_MS
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                registered = false
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
        }
    }

    private suspend fun handleRun(request: RunRequestDto) {
        if (currentRequest != null || engine.isActive) {
            report(request.id, "FAILED", "error" to "The phone is busy with another voice session")
            return
        }
        val flow = request.flowId?.let { repository.loadFlow(it) }
        if (flow == null) {
            report(request.id, "FAILED", "error" to "This flow no longer exists")
            return
        }
        follow(request.id, flow)
    }

    /** Buffers the engine's events from now on (subscribe before starting so none are missed). */
    private fun subscribe(): Pair<Channel<EngineEvent>, Job> {
        val buffer = Channel<EngineEvent>(Channel.UNLIMITED)
        val collector = scope.launch(start = CoroutineStart.UNDISPATCHED) { engine.events.collect { buffer.trySend(it) } }
        return buffer to collector
    }

    /** Starts [flow] and forwards its events to the run log of [requestId] until it ends. */
    private fun follow(requestId: String, flow: FlowDefinition) {
        val subscription = subscribe()
        when (val result = launcher.launch(flow)) {
            LaunchResult.STARTED -> Unit
            else -> {
                subscription.second.cancel()
                scope.launch { report(requestId, "FAILED", "error" to reason(result)) }
                return
            }
        }
        stream(requestId, subscription)
    }

    /** Forwards buffered engine events to the run log of [requestId] until the session ends. */
    private fun stream(requestId: String, subscription: Pair<Channel<EngineEvent>, Job>) {
        val (buffer, collector) = subscription
        currentRequest = requestId
        scope.launch {
            try {
                report(requestId, "RUNNING", "status" to "Running on the phone")
                var finished = false
                while (!finished) {
                    val batch = mutableListOf(buffer.receive())
                    while (batch.size < MAX_BATCH) batch += buffer.tryReceive().getOrNull() ?: break
                    val final = batch.firstNotNullOfOrNull { it.status }
                    report(requestId, final?.name, *batch.map { it.kind to it.message }.toTypedArray(), retries = if (final != null) FINAL_RETRIES else 1)
                    finished = final != null
                    if (!finished) delay(BATCH_DELAY_MS)
                }
            } finally {
                collector.cancel()
                if (currentRequest == requestId) currentRequest = null
            }
        }
    }

    private suspend fun report(requestId: String, status: String?, vararg events: Pair<String, String>, retries: Int = 1) {
        repeat(retries) { attempt ->
            val ok = runCatching { repository.report(requestId, status, events.toList()) }.isSuccess
            if (ok) return
            if (attempt < retries - 1) delay(MIN_BACKOFF_MS * (attempt + 1))
        }
    }

    private fun reason(result: LaunchResult) = when (result) {
        LaunchResult.BUSY -> "The phone is busy with another voice session"
        LaunchResult.SERVICE_OFF -> "The VoiceControl accessibility service is off"
        LaunchResult.NO_MIC_PERMISSION -> "Microphone permission is missing on the phone"
        LaunchResult.STARTED -> "Started"
    }

    private companion object {
        const val POLL_WAIT_SECONDS = 25
        const val MIN_BACKOFF_MS = 2_000L
        const val MAX_BACKOFF_MS = 60_000L
        const val DISABLED_RECHECK_MS = 30_000L
        const val TRIGGER_REFRESH_MS = 10 * 60_000L
        const val APP_OPEN_SETTLE_MS = 1_500L
        const val APP_OPEN_COOLDOWN_MS = 2 * 60_000L
        const val BATCH_DELAY_MS = 700L
        const val MAX_BATCH = 40
        const val FINAL_RETRIES = 5
    }
}
