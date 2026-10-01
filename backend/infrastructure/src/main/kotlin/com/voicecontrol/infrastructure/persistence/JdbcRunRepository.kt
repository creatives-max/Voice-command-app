package com.voicecontrol.infrastructure.persistence

import com.voicecontrol.domain.ai.Language
import com.voicecontrol.domain.history.Run
import com.voicecontrol.domain.history.RunRepository
import com.voicecontrol.domain.history.RunStats
import com.voicecontrol.domain.history.RunStatus
import com.voicecontrol.domain.history.ScreenRecord
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.sql.ResultSet
import java.util.UUID

class JdbcRunRepository(private val db: Database) : RunRepository {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val screens = ListSerializer(ScreenRecord.serializer())

    override suspend fun insertAll(runs: List<Run>): Int = db.tx {
        runs.sumOf { r ->
            update(
                """
                INSERT INTO runs (id, user_id, app_package, started_at, ended_at, status, language, filled_count, step_count, screens)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb) ON CONFLICT (id) DO NOTHING
                """.trimIndent(),
                r.id, r.userId, r.appPackage, r.startedAt, r.endedAt, r.status.name, r.language.name, r.filledCount, r.stepCount,
                json.encodeToString(screens, r.screens),
            )
        }
    }

    override suspend fun list(userId: UUID, appPackage: String?, limit: Int, offset: Int): List<Run> = db.tx {
        if (appPackage == null) {
            query("SELECT * FROM runs WHERE user_id = ? ORDER BY started_at DESC LIMIT ? OFFSET ?", userId, limit, offset, map = ::toRun)
        } else {
            query("SELECT * FROM runs WHERE user_id = ? AND app_package = ? ORDER BY started_at DESC LIMIT ? OFFSET ?", userId, appPackage, limit, offset, map = ::toRun)
        }
    }

    override suspend fun get(userId: UUID, id: UUID): Run? = db.tx {
        query("SELECT * FROM runs WHERE user_id = ? AND id = ?", userId, id, map = ::toRun).firstOrNull()
    }

    override suspend fun delete(userId: UUID, id: UUID): Boolean = db.tx { update("DELETE FROM runs WHERE user_id = ? AND id = ?", userId, id) > 0 }

    override suspend fun deleteAll(userId: UUID): Int = db.tx { update("DELETE FROM runs WHERE user_id = ?", userId) }

    override suspend fun stats(userId: UUID): RunStats = db.tx {
        val totals = query(
            "SELECT count(*) AS total, count(*) FILTER (WHERE status = 'COMPLETED') AS completed, coalesce(sum(filled_count), 0) AS filled FROM runs WHERE user_id = ?",
            userId,
        ) { Triple(it.getInt("total"), it.getInt("completed"), it.getInt("filled")) }.first()
        val top = query(
            "SELECT app_package, count(*) AS n FROM runs WHERE user_id = ? GROUP BY app_package ORDER BY n DESC LIMIT 5",
            userId,
        ) { it.getString("app_package") to it.getInt("n") }
        RunStats(totals.first, totals.second, totals.third, top)
    }

    private fun toRun(rs: ResultSet) = Run(
        id = rs.uuid("id"),
        userId = rs.uuid("user_id"),
        appPackage = rs.getString("app_package"),
        startedAt = rs.instant("started_at"),
        endedAt = rs.instant("ended_at"),
        status = runCatching { RunStatus.valueOf(rs.getString("status")) }.getOrDefault(RunStatus.COMPLETED),
        language = runCatching { Language.valueOf(rs.getString("language")) }.getOrDefault(Language.ENGLISH),
        screens = json.decodeFromString(screens, rs.getString("screens")),
    )
}
