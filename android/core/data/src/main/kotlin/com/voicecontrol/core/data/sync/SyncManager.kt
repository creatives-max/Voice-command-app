package com.voicecontrol.core.data.sync

import com.voicecontrol.core.data.flows.FlowRepository
import com.voicecontrol.core.data.history.HistoryRepository
import com.voicecontrol.core.network.HistoryApi
import com.voicecontrol.core.data.profile.ProfileRepository
import com.voicecontrol.core.network.ApiException
import com.voicecontrol.core.network.FlowApi
import com.voicecontrol.core.network.ProfileApi
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

data class SyncReport(val uploadedFlows: Int = 0, val failures: Int = 0, val unauthorized: Boolean = false)

/**
 * Keeps the phone and the backend in step:
 * - pushes local profile edits (or pulls the server profile),
 * - uploads flows recorded on the phone (the server copy then replaces the local draft),
 * - refreshes cached server flows so dashboard edits reach the phone,
 * - registers the phone for remote runs and refreshes its app-open triggers.
 */
@Singleton
class SyncManager @Inject constructor(
    private val profiles: ProfileRepository,
    private val profileApi: ProfileApi,
    private val flows: FlowRepository,
    private val flowApi: FlowApi,
    private val history: HistoryRepository,
    private val historyApi: HistoryApi,
    private val remoteRuns: com.voicecontrol.core.data.automation.RemoteRunRepository,
) {
    suspend fun syncAll(pullProfile: Boolean): SyncReport {
        var failures = 0
        var uploaded = 0
        try {
            syncProfile(pullProfile)
        } catch (e: ApiException) {
            if (e.isUnauthorized) return SyncReport(unauthorized = true)
            failures++
        }
        for (local in flows.unsynced()) {
            try {
                if (local.isSynced) {
                    flows.save(local, synced = true)
                    continue
                }
                flows.replace(local.id, flowApi.create(local))
                uploaded++
            } catch (e: ApiException) {
                if (e.isUnauthorized) return SyncReport(uploaded, failures, unauthorized = true)
                failures++
            }
        }
        failures += refreshServerFlows()
        try {
            uploadHistory()
        } catch (e: ApiException) {
            if (e.isUnauthorized) return SyncReport(uploaded, failures, unauthorized = true)
            failures++
        }
        try {
            // Keeps this phone listed in the dashboard and its app-open triggers current.
            remoteRuns.register()
            remoteRuns.refreshTriggers()
        } catch (e: ApiException) {
            if (e.isUnauthorized) return SyncReport(uploaded, failures, unauthorized = true)
            failures++
        }
        return SyncReport(uploaded, failures)
    }

    /** Uploads finished sessions in batches; the server ignores duplicates, so retries are safe. */
    private suspend fun uploadHistory() {
        history.unsynced().chunked(HISTORY_BATCH).forEach { batch ->
            historyApi.upload(batch)
            history.markSynced(batch.map { it.sessionId })
        }
    }

    private suspend fun syncProfile(pull: Boolean) {
        if (profiles.isDirty()) {
            val local = profiles.profile.first()
            profiles.saveSynced(profileApi.put(local))
        } else if (pull) {
            profiles.saveSynced(profileApi.get())
        }
    }

    /** Pulls newer versions of server flows (e.g. edited in the dashboard). Returns the number of failures. */
    suspend fun refreshServerFlows(): Int {
        val remote = try {
            flowApi.list()
        } catch (e: ApiException) {
            return 1
        }
        var failures = 0
        val remoteIds = remote.map { it.id }.toSet()
        val cached = flows.syncedSnapshot()
        for (summary in remote) {
            val local = cached[summary.id]
            if (local != null && local.version >= summary.currentVersion) continue
            try {
                flows.save(flowApi.get(summary.id), synced = true)
            } catch (e: ApiException) {
                failures++
            }
        }
        // Flows deleted on the server disappear from the phone too.
        cached.keys.filter { it !in remoteIds }.forEach { flows.delete(it) }
        return failures
    }

    private companion object {
        const val HISTORY_BATCH = 50
    }
}
