package com.voicecontrol.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.voicecontrol.core.engine.port.SessionConfig
import com.voicecontrol.core.engine.port.SessionConfigProvider
import com.voicecontrol.core.model.Language
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class SettingsRepository @Inject constructor(
    @Named("settings") private val store: DataStore<Preferences>,
) : SessionConfigProvider {

    private object Keys {
        val language = stringPreferencesKey("language")
        val speechRate = floatPreferencesKey("speech_rate")
        val transliterate = booleanPreferencesKey("transliterate")
        val confirmValues = booleanPreferencesKey("confirm_values")
        val askBeforeSubmit = booleanPreferencesKey("ask_before_submit")
        val skipFilled = booleanPreferencesKey("skip_filled")
        val localOnly = booleanPreferencesKey("local_only")
        val saveHistory = booleanPreferencesKey("save_history")
        val showOverlay = booleanPreferencesKey("show_overlay")
        val visionFallback = booleanPreferencesKey("vision_fallback")
        val autoStart = booleanPreferencesKey("auto_start_with_flow")
        val backendUrl = stringPreferencesKey("backend_url")
        val dashboardUrl = stringPreferencesKey("dashboard_url")
    }

    val settings: Flow<AppSettings> = store.data.map(::read)

    /** Current settings snapshot. */
    suspend fun appSettings(): AppSettings = settings.first()

    override suspend fun current(): SessionConfig = appSettings().toSessionConfig()

    /** Dashboard address to open from the app. */
    suspend fun dashboardUrl(): String =
        appSettings().dashboardUrl.ifBlank { com.voicecontrol.core.network.BuildConfig.DEFAULT_DASHBOARD_URL }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { p ->
            val next = transform(read(p))
            p[Keys.language] = next.language.name
            p[Keys.speechRate] = next.speechRate
            p[Keys.transliterate] = next.transliterate
            p[Keys.confirmValues] = next.confirmValues
            p[Keys.askBeforeSubmit] = next.askBeforeSubmit
            p[Keys.skipFilled] = next.skipFilledFields
            p[Keys.localOnly] = next.localOnly
            p[Keys.saveHistory] = next.saveHistory
            p[Keys.showOverlay] = next.showOverlay
            p[Keys.visionFallback] = next.visionFallback
            p[Keys.autoStart] = next.autoStartWithFlow
            p[Keys.backendUrl] = next.backendUrl.trim().trimEnd('/')
            p[Keys.dashboardUrl] = next.dashboardUrl.trim().trimEnd('/')
        }
    }

    private fun read(p: Preferences): AppSettings {
        val d = AppSettings()
        return AppSettings(
            language = p[Keys.language]?.let { runCatching { Language.valueOf(it) }.getOrNull() } ?: d.language,
            speechRate = p[Keys.speechRate] ?: d.speechRate,
            transliterate = p[Keys.transliterate] ?: d.transliterate,
            confirmValues = p[Keys.confirmValues] ?: d.confirmValues,
            askBeforeSubmit = p[Keys.askBeforeSubmit] ?: d.askBeforeSubmit,
            skipFilledFields = p[Keys.skipFilled] ?: d.skipFilledFields,
            localOnly = p[Keys.localOnly] ?: d.localOnly,
            saveHistory = p[Keys.saveHistory] ?: d.saveHistory,
            showOverlay = p[Keys.showOverlay] ?: d.showOverlay,
            visionFallback = p[Keys.visionFallback] ?: d.visionFallback,
            autoStartWithFlow = p[Keys.autoStart] ?: d.autoStartWithFlow,
            backendUrl = p[Keys.backendUrl] ?: d.backendUrl,
            dashboardUrl = p[Keys.dashboardUrl] ?: d.dashboardUrl,
        )
    }
}
