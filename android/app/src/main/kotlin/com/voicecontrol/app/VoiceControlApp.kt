package com.voicecontrol.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.voicecontrol.core.data.sync.SyncScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class VoiceControlApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var syncScheduler: SyncScheduler

    @Inject lateinit var crashReporter: com.voicecontrol.core.data.diagnostics.CrashReporter

    @Inject lateinit var crashStore: com.voicecontrol.core.data.diagnostics.CrashStore

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        crashReporter.install()
        syncScheduler.schedulePeriodic()
        // Reports saved by a previous crash go out with the next sync (only if crash reports are on).
        if (crashStore.hasPending()) syncScheduler.syncNow()
    }
}
