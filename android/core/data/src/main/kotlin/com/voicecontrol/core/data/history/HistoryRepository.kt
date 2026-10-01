package com.voicecontrol.core.data.history

import com.voicecontrol.core.data.StorageJson
import com.voicecontrol.core.data.db.HistoryDao
import com.voicecontrol.core.data.db.HistoryEntity
import com.voicecontrol.core.model.Language
import com.voicecontrol.core.model.RunStatus
import com.voicecontrol.core.model.ScreenRecord
import com.voicecontrol.core.model.SessionSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HistoryRepository @Inject constructor(
    private val dao: HistoryDao,
) {
    private val screensSerializer = ListSerializer(ScreenRecord.serializer())

    fun observeRecent(limit: Int = 200): Flow<List<SessionSummary>> = dao.observeRecent(limit).map { rows -> rows.map(::toModel) }

    suspend fun get(sessionId: String): SessionSummary? = dao.byId(sessionId)?.let(::toModel)

    suspend fun add(summary: SessionSummary) = dao.insert(toEntity(summary, synced = false))

    suspend fun unsynced(): List<SessionSummary> = dao.unsynced().map(::toModel)

    suspend fun markSynced(ids: List<String>) {
        if (ids.isNotEmpty()) dao.markSynced(ids)
    }

    suspend fun delete(sessionId: String) = dao.delete(sessionId)

    suspend fun clear() = dao.clear()

    private fun toModel(e: HistoryEntity) = SessionSummary(
        sessionId = e.sessionId,
        appPackage = e.appPackage,
        startedAtMillis = e.startedAtMillis,
        endedAtMillis = e.endedAtMillis,
        status = runCatching { RunStatus.valueOf(e.status) }.getOrDefault(RunStatus.COMPLETED),
        language = runCatching { Language.valueOf(e.language) }.getOrDefault(Language.ENGLISH),
        screens = runCatching { StorageJson.decodeFromString(screensSerializer, e.screensJson) }.getOrDefault(emptyList()),
    )

    private fun toEntity(s: SessionSummary, synced: Boolean) = HistoryEntity(
        sessionId = s.sessionId,
        appPackage = s.appPackage,
        startedAtMillis = s.startedAtMillis,
        endedAtMillis = s.endedAtMillis,
        status = s.status.name,
        language = s.language.name,
        filledCount = s.filledCount,
        screensJson = StorageJson.encodeToString(screensSerializer, s.screens),
        synced = synced,
    )
}
