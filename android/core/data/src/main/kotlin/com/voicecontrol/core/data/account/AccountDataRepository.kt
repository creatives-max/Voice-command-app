package com.voicecontrol.core.data.account

import android.content.Context
import android.net.Uri
import com.voicecontrol.core.data.auth.TokenStore
import com.voicecontrol.core.data.diagnostics.CrashStore
import com.voicecontrol.core.data.flows.FlowRepository
import com.voicecontrol.core.data.history.HistoryRepository
import com.voicecontrol.core.data.profile.ProfileRepository
import com.voicecontrol.core.data.settings.AppSettings
import com.voicecontrol.core.data.settings.SettingsRepository
import com.voicecontrol.core.model.FlowDefinition
import com.voicecontrol.core.model.SessionSummary
import com.voicecontrol.core.model.UserProfile
import com.voicecontrol.core.network.ApiException
import com.voicecontrol.core.network.AuthApi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/** Data export, account deletion and wiping the phone (GDPR access and erasure). */
@Singleton
class AccountDataRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val flows: FlowRepository,
    private val history: HistoryRepository,
    private val profiles: ProfileRepository,
    private val settings: SettingsRepository,
    private val tokens: TokenStore,
    private val crashes: CrashStore,
    private val authApi: AuthApi,
    private val answers: com.voicecontrol.core.data.memory.AnswerMemoryRepository,
) {
    private val json = Json { encodeDefaults = true; explicitNulls = false; prettyPrint = true }

    /**
     * Everything on this phone (settings, profile, flows, history) plus, when signed in and not on-device
     * only, what the server stores. Written as JSON to [target] (a document the user picked).
     */
    suspend fun exportTo(target: Uri): ExportResult = withContext(Dispatchers.IO) {
        val s = settings.appSettings()
        var serverIncluded = false
        val server: JsonElement? = if (tokens.tokens() != null && !s.localOnly) {
            runCatching { json.parseToJsonElement(authApi.export()) }.getOrNull()?.also { serverIncluded = true }
        } else {
            null
        }
        val doc = buildJsonObject {
            put("exportedAtMillis", System.currentTimeMillis())
            put("onThisPhone", buildJsonObject {
                put("settings", settingsJson(s))
                put("profile", json.encodeToJsonElement(UserProfile.serializer(), profiles.profile.first()))
                put("flows", json.encodeToJsonElement(ListSerializer(FlowDefinition.serializer()), flows.observeFlows().first()))
                put("history", json.encodeToJsonElement(ListSerializer(SessionSummary.serializer()), history.observeRecent(10_000).first()))
                put(
                    "rememberedAnswers",
                    json.encodeToJsonElement(ListSerializer(com.voicecontrol.core.data.memory.RememberedAnswer.serializer()), answers.answers.first()),
                )
                settings.localShortcutsJson.first()?.let { put("voiceShortcuts", json.parseToJsonElement(it)) }
            })
            server?.let { put("server", it) }
        }
        val out = context.contentResolver.openOutputStream(target, "wt") ?: error("Can't write to the chosen file")
        out.use { it.write(json.encodeToString(JsonObject.serializer(), doc).toByteArray()) }
        ExportResult(serverIncluded)
    }

    /** Deletes the account on the server, then everything on this phone. */
    suspend fun deleteAccount(password: String): Result<Unit> = runCatching {
        try {
            authApi.deleteAccount(password)
        } catch (e: ApiException) {
            throw IllegalStateException(
                when {
                    e.status == 403 -> "That password is not correct"
                    e.isNetwork -> "Can't reach the VoiceControl server"
                    else -> e.message ?: "Deletion failed"
                },
            )
        }
        wipePhone()
    }

    /** Removes all VoiceControl data from this phone (flows, history, profile, sign-in, settings, crash reports). */
    suspend fun wipePhone() {
        flows.clear()
        history.clear()
        profiles.clear()
        tokens.clear()
        crashes.clear()
        settings.reset()
    }

    private fun settingsJson(s: AppSettings) = buildJsonObject {
        put("language", s.language.name)
        put("localOnly", s.localOnly)
        put("saveHistory", s.saveHistory)
        put("visionFallback", s.visionFallback)
        put("smartMode", s.smartMode)
        put("remoteRuns", s.remoteRuns)
        put("deviceName", JsonPrimitive(s.deviceName))
        put("wakeWordEnabled", s.wakeWordEnabled)
        put("announceCalls", s.announceCalls)
        put("appLock", s.appLock)
        put("crashReports", s.crashReports)
        put("backendUrl", JsonPrimitive(s.backendUrl))
    }
}

data class ExportResult(val serverIncluded: Boolean)
