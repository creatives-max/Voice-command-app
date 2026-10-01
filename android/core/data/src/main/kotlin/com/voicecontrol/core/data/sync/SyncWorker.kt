package com.voicecontrol.core.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.voicecontrol.core.data.auth.TokenStore
import com.voicecontrol.core.data.settings.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** Background sync with the backend; does nothing when signed out or in local-only mode. */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val tokens: TokenStore,
    private val settings: SettingsRepository,
    private val sync: SyncManager,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (tokens.tokens() == null || settings.appSettings().localOnly) return Result.success()
        val report = sync.syncAll(pullProfile = inputData.getBoolean(KEY_PULL_PROFILE, false))
        return when {
            report.unauthorized -> Result.success() // user must sign in again; nothing to retry
            report.failures > 0 && runAttemptCount < MAX_ATTEMPTS -> Result.retry()
            else -> Result.success()
        }
    }

    companion object {
        const val KEY_PULL_PROFILE = "pull_profile"
        private const val MAX_ATTEMPTS = 5
    }
}
