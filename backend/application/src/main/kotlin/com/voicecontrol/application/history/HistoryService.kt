package com.voicecontrol.application.history

import com.voicecontrol.domain.common.DomainException
import com.voicecontrol.domain.history.Run
import com.voicecontrol.domain.history.RunRepository
import com.voicecontrol.domain.history.RunStats
import java.util.UUID

class HistoryService(private val runs: RunRepository) {

    suspend fun upload(userId: UUID, batch: List<Run>): Int {
        if (batch.size > MAX_BATCH) throw DomainException.Validation("At most $MAX_BATCH runs per upload")
        batch.forEach { run ->
            if (run.userId != userId) throw DomainException.Forbidden()
            if (run.endedAt.isBefore(run.startedAt)) throw DomainException.Validation("Run ${run.id} ends before it starts")
            if (run.screens.size > MAX_SCREENS || run.stepCount > MAX_STEPS) throw DomainException.Validation("Run ${run.id} is too large")
        }
        return runs.insertAll(batch)
    }

    suspend fun list(userId: UUID, appPackage: String?, limit: Int, offset: Int): List<Run> =
        runs.list(userId, appPackage?.takeIf { it.isNotBlank() }, limit.coerceIn(1, 200), offset.coerceAtLeast(0))

    suspend fun get(userId: UUID, id: UUID): Run = runs.get(userId, id) ?: throw DomainException.NotFound("Run not found")

    suspend fun delete(userId: UUID, id: UUID) {
        if (!runs.delete(userId, id)) throw DomainException.NotFound("Run not found")
    }

    suspend fun clear(userId: UUID): Int = runs.deleteAll(userId)

    suspend fun stats(userId: UUID): RunStats = runs.stats(userId)

    private companion object {
        const val MAX_BATCH = 100
        const val MAX_SCREENS = 20
        const val MAX_STEPS = 500
    }
}
