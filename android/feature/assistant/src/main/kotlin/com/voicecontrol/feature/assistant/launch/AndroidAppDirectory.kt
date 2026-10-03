package com.voicecontrol.feature.assistant.launch

import android.content.Context
import android.content.Intent
import com.voicecontrol.core.engine.port.AppDirectory
import com.voicecontrol.core.engine.port.InstalledApp
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The phone's apps that have a launcher icon, for "open WhatsApp" (refreshed every minute), and how often
 * each was opened by voice (kept on the phone only) to suggest the user's usual apps.
 */
@Singleton
class AndroidAppDirectory @Inject constructor(@ApplicationContext private val context: Context) : AppDirectory {
    private var cached: List<InstalledApp> = emptyList()
    private var cachedAt = 0L

    override suspend fun apps(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (cached.isNotEmpty() && now - cachedAt < CACHE_MS) return@withContext cached
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
            .map { InstalledApp(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .filter { it.packageName != context.packageName && it.label.isNotBlank() }
            .distinctBy { it.packageName }
        cached = apps
        cachedAt = now
        apps
    }

    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    override suspend fun opened(packageName: String) = withContext(Dispatchers.IO) {
        prefs.edit().putInt(packageName, prefs.getInt(packageName, 0) + 1).apply()
    }

    override suspend fun favourites(limit: Int): List<InstalledApp> {
        val counts = withContext(Dispatchers.IO) { prefs.all.mapNotNull { (k, v) -> (v as? Int)?.let { k to it } }.toMap() }
        if (counts.isEmpty()) return emptyList()
        return apps().filter { (counts[it.packageName] ?: 0) >= MIN_OPENS }
            .sortedByDescending { counts[it.packageName] ?: 0 }
            .take(limit)
    }

    private companion object {
        const val CACHE_MS = 60_000L
        const val PREFS = "voicecontrol_app_opens"
        /** Opened at least this often before it counts as a usual app. */
        const val MIN_OPENS = 2
    }
}
