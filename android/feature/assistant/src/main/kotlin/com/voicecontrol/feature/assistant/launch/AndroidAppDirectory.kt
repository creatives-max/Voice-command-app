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

/** The phone's apps that have a launcher icon, for "open WhatsApp" (refreshed every minute). */
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

    private companion object {
        const val CACHE_MS = 60_000L
    }
}
