package com.voicecontrol.core.data.ai

import com.voicecontrol.core.data.settings.SettingsRepository
import com.voicecontrol.core.engine.port.BackendWarmup
import com.voicecontrol.core.network.ApiClient
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Pings the server's health check when a voice session starts (at most every few minutes). */
@Singleton
class BackendWarmer @Inject constructor(
    private val api: ApiClient,
    private val settings: SettingsRepository,
) : BackendWarmup {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var lastAt = 0L

    override fun warm() {
        val now = System.currentTimeMillis()
        if (now - lastAt < MIN_GAP_MS) return
        lastAt = now
        scope.launch {
            if (settings.appSettings().localOnly) return@launch
            runCatching { api.send(HttpMethod.Get, "/health", authenticated = false) }
        }
    }

    private companion object {
        /** The free plan sleeps after 15 minutes without requests; a session in between keeps it awake. */
        const val MIN_GAP_MS = 4 * 60_000L
    }
}
