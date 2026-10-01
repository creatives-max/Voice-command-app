package com.voicecontrol.core.data.automation

import com.voicecontrol.core.data.auth.AuthRepository
import com.voicecontrol.core.data.flows.FlowRepository
import com.voicecontrol.core.data.settings.SettingsRepository
import com.voicecontrol.core.model.FlowDefinition
import com.voicecontrol.core.network.ApiException
import com.voicecontrol.core.network.AutomationApi
import com.voicecontrol.core.network.FlowApi
import com.voicecontrol.core.network.NetworkJson
import com.voicecontrol.core.network.dto.DeviceCommandsDto
import com.voicecontrol.core.network.dto.RegisterDeviceDto
import com.voicecontrol.core.network.dto.RunEventInDto
import com.voicecontrol.core.network.dto.RunReportDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import javax.inject.Inject
import javax.inject.Singleton

/** A flow that starts by itself when its app comes to the foreground. */
@Serializable
data class AppOpenTrigger(val id: String, val flowId: String, val appPackage: String)

/**
 * The phone's side of remote runs: registers this phone, long-polls for flows to run or stop,
 * reports status and value-free log lines, and keeps the app-open triggers (cached for offline use).
 */
@Singleton
class RemoteRunRepository @Inject constructor(
    private val api: AutomationApi,
    private val flowApi: FlowApi,
    private val flows: FlowRepository,
    private val settings: SettingsRepository,
    private val auth: AuthRepository,
    private val deviceInfo: DeviceInfo,
) {
    private val triggers = MutableStateFlow<List<AppOpenTrigger>>(emptyList())
    val appOpenTriggers: StateFlow<List<AppOpenTrigger>> = triggers.asStateFlow()

    private val serializer = ListSerializer(AppOpenTrigger.serializer())

    /** Signed in, online mode, and remote runs allowed. */
    suspend fun remoteEnabled(): Boolean {
        val s = settings.appSettings()
        return !s.localOnly && s.remoteRuns && auth.user.first() != null
    }

    /** Signed in and online (app-open triggers still work with remote runs off). */
    suspend fun online(): Boolean = !settings.appSettings().localOnly && auth.user.first() != null

    suspend fun deviceId(): String = settings.deviceId()

    suspend fun register() {
        val s = settings.appSettings()
        api.register(
            RegisterDeviceDto(
                id = deviceId(),
                name = s.deviceName.ifBlank { deviceInfo.model },
                appVersion = deviceInfo.appVersion,
                remoteRuns = s.remoteRuns,
            ),
        )
    }

    suspend fun poll(waitSeconds: Int): DeviceCommandsDto = api.commands(deviceId(), waitSeconds)

    /** Loads the cached triggers, then refreshes them from the server when online. */
    suspend fun refreshTriggers() {
        if (triggers.value.isEmpty()) {
            settings.appOpenTriggersJson()?.let { json ->
                runCatching { NetworkJson.decodeFromString(serializer, json) }.getOrNull()?.let { triggers.value = it }
            }
        }
        if (!online()) return
        val fresh = api.triggers(deviceId())
            .filter { it.type == "APP_OPEN" && it.enabled && it.appPackage != null }
            .map { AppOpenTrigger(it.id, it.flowId, it.appPackage!!) }
        triggers.value = fresh
        settings.saveAppOpenTriggersJson(NetworkJson.encodeToString(serializer, fresh))
    }

    fun triggerFor(appPackage: String): AppOpenTrigger? = triggers.value.firstOrNull { it.appPackage == appPackage }

    /** The current server version of a flow (cached for offline use), or the cached copy. */
    suspend fun loadFlow(flowId: String): FlowDefinition? = try {
        flowApi.get(flowId).also { flows.save(it, synced = true) }
    } catch (e: ApiException) {
        if (e.status == 404) null else flows.get(flowId)
    }

    suspend fun report(requestId: String, status: String?, events: List<Pair<String, String>>) {
        api.report(requestId, RunReportDto(deviceId(), status, events.map { RunEventInDto(it.first, it.second) }))
    }

    /** Tells the dashboard a flow started because its app opened; returns the run id for the live log. */
    suspend fun reportAppOpen(trigger: AppOpenTrigger): String = api.appOpenRun(deviceId(), trigger.flowId, trigger.id).id
}

/** Device facts used to register the phone (abstracted for tests). */
interface DeviceInfo {
    val model: String
    val appVersion: String?
}
