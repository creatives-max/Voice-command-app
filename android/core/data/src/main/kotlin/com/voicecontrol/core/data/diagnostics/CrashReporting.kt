package com.voicecontrol.core.data.diagnostics

import android.content.Context
import android.os.Build
import com.voicecontrol.core.data.automation.DeviceInfo
import com.voicecontrol.core.data.settings.SettingsRepository
import com.voicecontrol.core.model.diagnostics.CrashRecord
import com.voicecontrol.core.model.diagnostics.CrashRecords
import com.voicecontrol.core.network.CrashApi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Crash reports waiting to be uploaded, one JSON file each in the app's private storage. */
@Singleton
class CrashStore @Inject constructor(@ApplicationContext context: Context) {
    private val dir = File(context.filesDir, "crashes")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Blocking on purpose: called from the crashing thread just before the process dies. */
    fun save(record: CrashRecord) {
        runCatching {
            dir.mkdirs()
            // Keep at most the newest few reports.
            dir.listFiles()?.sortedBy { it.lastModified() }?.dropLast(MAX_FILES - 1)?.forEach { it.delete() }
            File(dir, "${record.id}.json").writeText(json.encodeToString(CrashRecord.serializer(), record))
        }
    }

    fun pending(): List<CrashRecord> =
        dir.listFiles { f -> f.extension == "json" }?.sortedBy { it.lastModified() }?.mapNotNull { f ->
            runCatching { json.decodeFromString(CrashRecord.serializer(), f.readText()) }.getOrElse { f.delete(); null }
        }.orEmpty()

    fun hasPending(): Boolean = dir.listFiles()?.isNotEmpty() == true

    fun remove(ids: Collection<String>) = ids.forEach { File(dir, "$it.json").delete() }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private companion object {
        const val MAX_FILES = 20
    }
}

/**
 * Records uncaught exceptions (only while crash reports are turned on) and lets the previous handler
 * finish the crash as usual. Reports are uploaded later by [CrashUploader].
 */
@Singleton
class CrashReporter @Inject constructor(
    private val store: CrashStore,
    private val settings: SettingsRepository,
    private val device: DeviceInfo,
) {
    @Volatile private var enabled = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun install() {
        scope.launch { settings.settings.map { it.crashReports && !it.localOnly }.distinctUntilChanged().collect { enabled = it } }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            if (enabled) {
                store.save(
                    CrashRecords.from(error, thread.name, device.appVersion, Build.VERSION.SDK_INT, device.model, System.currentTimeMillis(), UUID.randomUUID().toString()),
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }
}

/** Sends saved crash reports when allowed; anything not accepted stays for the next try. */
@Singleton
class CrashUploader @Inject constructor(
    private val store: CrashStore,
    private val settings: SettingsRepository,
    private val api: CrashApi,
) {
    suspend fun uploadPending(): Int {
        val s = settings.appSettings()
        if (!s.crashReports || s.localOnly) return 0
        val pending = store.pending()
        if (pending.isEmpty()) return 0
        var sent = 0
        pending.chunked(BATCH).forEach { batch ->
            api.upload(batch)
            store.remove(batch.map { it.id })
            sent += batch.size
        }
        return sent
    }

    private companion object {
        const val BATCH = 20
    }
}
