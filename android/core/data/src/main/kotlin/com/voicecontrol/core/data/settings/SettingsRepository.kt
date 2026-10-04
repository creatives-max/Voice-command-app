package com.voicecontrol.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
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
        val remoteRuns = booleanPreferencesKey("remote_runs")
        val deviceName = stringPreferencesKey("device_name")
        val deviceId = stringPreferencesKey("device_id")
        val appOpenTriggers = stringPreferencesKey("app_open_triggers")
        val voiceTriggers = stringPreferencesKey("voice_triggers")
        val localShortcuts = stringPreferencesKey("local_voice_shortcuts")
        val templates = stringPreferencesKey("starter_templates")
        val useTemplates = booleanPreferencesKey("use_templates")
        val bargeIn = booleanPreferencesKey("barge_in")
        val smartMode = booleanPreferencesKey("smart_mode")
        val offlineSpeech = booleanPreferencesKey("offline_speech")
        val rememberAnswers = booleanPreferencesKey("remember_answers")
        val rememberedAnswers = stringPreferencesKey("remembered_answers")
        val confirmLowConfidence = booleanPreferencesKey("confirm_low_confidence")
        val wakeWordEnabled = booleanPreferencesKey("wake_word_enabled")
        val announceCalls = booleanPreferencesKey("announce_calls")
        val wakeWord = stringPreferencesKey("wake_word")
        val confirmDestructive = booleanPreferencesKey("confirm_destructive")
        val appLock = booleanPreferencesKey("app_lock")
        val lockTimeout = intPreferencesKey("lock_timeout_seconds")
        val crashReports = booleanPreferencesKey("crash_reports")
        val onboardingDone = booleanPreferencesKey("onboarding_done")
        val themeMode = stringPreferencesKey("theme_mode")
    }

    val settings: Flow<AppSettings> = store.data.map(::read)

    /** Current settings snapshot. */
    suspend fun appSettings(): AppSettings = settings.first()

    override suspend fun current(): SessionConfig = appSettings().toSessionConfig()

    /** Dashboard address to open from the app. */
    suspend fun dashboardUrl(): String =
        appSettings().dashboardUrl.ifBlank { com.voicecontrol.core.network.BuildConfig.DEFAULT_DASHBOARD_URL }

    /** Stable random id of this installation, used to register the phone for remote runs. */
    suspend fun deviceId(): String {
        store.data.first()[Keys.deviceId]?.let { return it }
        var id = ""
        store.edit { p ->
            id = p[Keys.deviceId] ?: java.util.UUID.randomUUID().toString().also { p[Keys.deviceId] = it }
        }
        return id
    }

    /** App-open triggers last fetched from the server (JSON), so they work right after a restart. */
    suspend fun appOpenTriggersJson(): String? = store.data.first()[Keys.appOpenTriggers]

    suspend fun saveAppOpenTriggersJson(json: String) {
        store.edit { it[Keys.appOpenTriggers] = json }
    }

    /** Voice shortcuts set on the dashboard, last fetched from the server (JSON). */
    suspend fun voiceTriggersJson(): String? = store.data.first()[Keys.voiceTriggers]

    suspend fun saveVoiceTriggersJson(json: String) {
        store.edit { it[Keys.voiceTriggers] = json }
    }

    /** Voice shortcuts made on this phone (JSON). */
    val localShortcutsJson: Flow<String?> = store.data.map { it[Keys.localShortcuts] }

    suspend fun saveLocalShortcutsJson(json: String) {
        store.edit { it[Keys.localShortcuts] = json }
    }

    /** Answers remembered on this phone (JSON), see AnswerMemoryRepository. */
    val rememberedAnswersJson: Flow<String?> = store.data.map { it[Keys.rememberedAnswers] }

    suspend fun saveRememberedAnswersJson(json: String) {
        store.edit { it[Keys.rememberedAnswers] = json }
    }

    suspend fun templatesJson(): String? = store.data.first()[Keys.templates]

    suspend fun saveTemplatesJson(json: String) {
        store.edit { it[Keys.templates] = json }
    }

    /** Removes every setting (used when all data on the phone is deleted). The install id is kept. */
    suspend fun reset() {
        store.edit { p ->
            val id = p[Keys.deviceId]
            p.clear()
            id?.let { p[Keys.deviceId] = it }
        }
    }

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
            p[Keys.remoteRuns] = next.remoteRuns
            p[Keys.deviceName] = next.deviceName.trim().take(60)
            p[Keys.useTemplates] = next.useTemplates
            p[Keys.bargeIn] = next.bargeIn
            p[Keys.smartMode] = next.smartMode
            p[Keys.offlineSpeech] = next.offlineSpeech
            p[Keys.rememberAnswers] = next.rememberAnswers
            p[Keys.confirmLowConfidence] = next.confirmLowConfidence
            p[Keys.wakeWordEnabled] = next.wakeWordEnabled
            p[Keys.announceCalls] = next.announceCalls
            p[Keys.wakeWord] = next.wakeWord.trim().take(60)
            p[Keys.confirmDestructive] = next.confirmDestructive
            p[Keys.appLock] = next.appLock
            p[Keys.lockTimeout] = next.lockTimeoutSeconds.coerceIn(0, 86_400)
            p[Keys.crashReports] = next.crashReports
            p[Keys.onboardingDone] = next.onboardingDone
            p[Keys.themeMode] = next.themeMode.name
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
            remoteRuns = p[Keys.remoteRuns] ?: d.remoteRuns,
            deviceName = p[Keys.deviceName] ?: d.deviceName,
            useTemplates = p[Keys.useTemplates] ?: d.useTemplates,
            bargeIn = p[Keys.bargeIn] ?: d.bargeIn,
            smartMode = p[Keys.smartMode] ?: d.smartMode,
            offlineSpeech = p[Keys.offlineSpeech] ?: d.offlineSpeech,
            rememberAnswers = p[Keys.rememberAnswers] ?: d.rememberAnswers,
            confirmLowConfidence = p[Keys.confirmLowConfidence] ?: d.confirmLowConfidence,
            wakeWordEnabled = p[Keys.wakeWordEnabled] ?: d.wakeWordEnabled,
            announceCalls = p[Keys.announceCalls] ?: d.announceCalls,
            wakeWord = p[Keys.wakeWord]?.takeIf { it.isNotBlank() } ?: d.wakeWord,
            confirmDestructive = p[Keys.confirmDestructive] ?: d.confirmDestructive,
            appLock = p[Keys.appLock] ?: d.appLock,
            lockTimeoutSeconds = p[Keys.lockTimeout] ?: d.lockTimeoutSeconds,
            crashReports = p[Keys.crashReports] ?: d.crashReports,
            onboardingDone = p[Keys.onboardingDone] ?: d.onboardingDone,
            themeMode = com.voicecontrol.core.model.ThemeMode.parse(p[Keys.themeMode]),
        )
    }
}
