package com.voicecontrol.core.data.di

import android.content.Context
import android.os.Build
import com.voicecontrol.core.data.automation.DeviceInfo
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.voicecontrol.core.data.ai.CompositeInterpreter
import com.voicecontrol.core.data.ai.RemoteVisionDetector
import com.voicecontrol.core.engine.port.VisionDetector
import com.voicecontrol.core.data.auth.BackendSessionImpl
import com.voicecontrol.core.data.db.AppDatabase
import com.voicecontrol.core.data.db.FlowDao
import com.voicecontrol.core.data.db.HistoryDao
import com.voicecontrol.core.data.flows.LocalFlowSource
import com.voicecontrol.core.data.history.SessionRecorderImpl
import com.voicecontrol.core.data.profile.ProfileRepository
import com.voicecontrol.core.data.settings.SettingsRepository
import com.voicecontrol.core.engine.port.FlowSource
import com.voicecontrol.core.engine.port.Interpreter
import com.voicecontrol.core.engine.port.ProfileSource
import com.voicecontrol.core.engine.port.SessionConfigProvider
import com.voicecontrol.core.engine.port.SessionRecorder
import com.voicecontrol.core.network.BackendSession
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {
    @Binds abstract fun sessionConfig(impl: SettingsRepository): SessionConfigProvider
    @Binds abstract fun profileSource(impl: ProfileRepository): ProfileSource
    @Binds abstract fun flowSource(impl: LocalFlowSource): FlowSource
    @Binds abstract fun sessionRecorder(impl: SessionRecorderImpl): SessionRecorder
    @Binds abstract fun interpreter(impl: CompositeInterpreter): Interpreter
    @Binds abstract fun backendSession(impl: BackendSessionImpl): BackendSession
    @Binds abstract fun visionDetector(impl: RemoteVisionDetector): VisionDetector
    @Binds abstract fun shortcutSource(impl: com.voicecontrol.core.data.shortcuts.VoiceShortcutRepository): com.voicecontrol.core.engine.port.ShortcutSource

    companion object {
        @Provides
        fun deviceInfo(@ApplicationContext context: Context): DeviceInfo = object : DeviceInfo {
            override val model: String =
                if (Build.MODEL.startsWith(Build.MANUFACTURER, ignoreCase = true)) Build.MODEL else "${Build.MANUFACTURER} ${Build.MODEL}"
            override val appVersion: String? = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        }

        @Provides
        @Singleton
        fun database(@ApplicationContext context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME).build()

        @Provides fun flowDao(db: AppDatabase): FlowDao = db.flowDao()
        @Provides fun historyDao(db: AppDatabase): HistoryDao = db.historyDao()

        @Provides
        @Singleton
        @Named("settings")
        fun settingsStore(@ApplicationContext context: Context): DataStore<Preferences> =
            PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("voicecontrol_settings") }

        @Provides
        @Singleton
        @Named("auth")
        fun authStore(@ApplicationContext context: Context): DataStore<Preferences> =
            PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("voicecontrol_auth") }
    }
}
