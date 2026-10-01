package com.voicecontrol.infrastructure.persistence

import com.voicecontrol.domain.collab.AnalyticsRepository
import com.voicecontrol.domain.collab.CommentRepository
import com.voicecontrol.domain.collab.DailyRuns
import com.voicecontrol.domain.collab.FlowAnalytics
import com.voicecontrol.domain.collab.FlowComment
import com.voicecontrol.domain.collab.FlowUsage
import com.voicecontrol.domain.collab.LayoutRepository
import com.voicecontrol.domain.collab.NodePosition
import com.voicecontrol.domain.collab.StepStats
import com.voicecontrol.domain.history.RunStatus
import com.voicecontrol.domain.history.StepOutcome
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class JdbcAnalyticsRepository(private val db: Database) : AnalyticsRepository {

    /** JSONB containment filter matching runs with a screen that used [flowId] (served by the GIN index). */
    private fun usedFlow(flowId: UUID) = buildJsonArray { add(buildJsonObject { put("flowId", flowId.toString()) }) }.toString()

    override suspend fun flowAnalytics(flowId: UUID, since: Instant, zone: String): FlowAnalytics = db.tx {
        val filter = usedFlow(flowId)
        val usage = query(
            """
            SELECT count(*) AS runs,
                   count(*) FILTER (WHERE status = 'COMPLETED') AS completed,
                   count(*) FILTER (WHERE status = 'STOPPED') AS stopped,
                   count(*) FILTER (WHERE status = 'FAILED') AS failed,
                   count(DISTINCT user_id) AS users,
                   avg(extract(epoch FROM ended_at - started_at) * 1000) AS avg_ms,
                   max(started_at) AS last_run
            FROM runs WHERE screens @> ?::jsonb AND started_at >= ?
            """.trimIndent(),
            filter, since, map = ::toUsage,
        ).first()
        val daily = query(
            """
            SELECT (started_at AT TIME ZONE ?)::date AS day, status, count(*) AS n
            FROM runs WHERE screens @> ?::jsonb AND started_at >= ? GROUP BY 1, 2
            """.trimIndent(),
            zone, filter, since,
        ) { Triple(it.getObject("day", LocalDate::class.java), it.getString("status"), it.getInt("n")) }
        val steps = query(
            """
            SELECT st->>'elementId' AS element_id, max(st->>'label') AS label, st->>'outcome' AS outcome, count(*) AS n
            FROM runs r
            CROSS JOIN LATERAL jsonb_array_elements(r.screens) sc
            CROSS JOIN LATERAL jsonb_array_elements(sc->'steps') st
            WHERE r.screens @> ?::jsonb AND r.started_at >= ? AND sc->>'flowId' = ?
            GROUP BY 1, 3
            """.trimIndent(),
            filter, since, flowId.toString(),
        ) { Triple(it.getString("element_id") to it.getString("label"), it.getString("outcome"), it.getInt("n")) }
        val interpreted = query(
            """
            SELECT st->>'interpretedBy' AS source, count(*) AS n
            FROM runs r
            CROSS JOIN LATERAL jsonb_array_elements(r.screens) sc
            CROSS JOIN LATERAL jsonb_array_elements(sc->'steps') st
            WHERE r.screens @> ?::jsonb AND r.started_at >= ? AND sc->>'flowId' = ? AND st->>'interpretedBy' IS NOT NULL
            GROUP BY 1
            """.trimIndent(),
            filter, since, flowId.toString(),
        ) { it.getString("source") to it.getInt("n") }.toMap()
        val remote = query(
            "SELECT status, count(*) AS n FROM run_requests WHERE flow_id = ? AND created_at >= ? GROUP BY status",
            flowId, since,
        ) { it.getString("status") to it.getInt("n") }.toMap()

        val stepStats = steps.groupBy { it.first.first }.map { (elementId, rows) ->
            StepStats(
                elementId,
                rows.first().first.second ?: elementId,
                rows.mapNotNull { r -> runCatching { StepOutcome.valueOf(r.second) }.getOrNull()?.let { it to r.third } }.toMap(),
            )
        }.sortedByDescending { it.total }
        FlowAnalytics(usage, fillDays(daily, since, zone), stepStats, interpreted, remote)
    }

    override suspend fun overview(flowIds: List<UUID>, since: Instant, zone: String): Pair<Map<UUID, FlowUsage>, List<DailyRuns>> = db.tx {
        val ids = createArrayOf("text", flowIds.map { it.toString() }.toTypedArray())
        val usage = query(
            """
            WITH used AS (
                SELECT DISTINCT r.id, r.user_id, r.status, r.started_at, r.ended_at, sc->>'flowId' AS flow_id
                FROM runs r CROSS JOIN LATERAL jsonb_array_elements(r.screens) sc
                WHERE r.started_at >= ? AND sc->>'flowId' = ANY(?)
            )
            SELECT flow_id, count(*) AS runs,
                   count(*) FILTER (WHERE status = 'COMPLETED') AS completed,
                   count(*) FILTER (WHERE status = 'STOPPED') AS stopped,
                   count(*) FILTER (WHERE status = 'FAILED') AS failed,
                   count(DISTINCT user_id) AS users,
                   avg(extract(epoch FROM ended_at - started_at) * 1000) AS avg_ms,
                   max(started_at) AS last_run
            FROM used GROUP BY flow_id
            """.trimIndent(),
            since, ids,
        ) { UUID.fromString(it.getString("flow_id")) to toUsage(it) }.toMap()
        val daily = query(
            """
            SELECT (r.started_at AT TIME ZONE ?)::date AS day, r.status, count(*) AS n
            FROM runs r
            WHERE r.started_at >= ? AND EXISTS (SELECT 1 FROM jsonb_array_elements(r.screens) sc WHERE sc->>'flowId' = ANY(?))
            GROUP BY 1, 2
            """.trimIndent(),
            zone, since, ids,
        ) { Triple(it.getObject("day", LocalDate::class.java), it.getString("status"), it.getInt("n")) }
        usage to fillDays(daily, since, zone)
    }

    override suspend fun totals(flowIds: List<UUID>, from: Instant, until: Instant): FlowUsage = db.tx {
        val ids = createArrayOf("text", flowIds.map { it.toString() }.toTypedArray())
        query(
            """
            SELECT count(*) AS runs,
                   count(*) FILTER (WHERE status = 'COMPLETED') AS completed,
                   count(*) FILTER (WHERE status = 'STOPPED') AS stopped,
                   count(*) FILTER (WHERE status = 'FAILED') AS failed,
                   count(DISTINCT user_id) AS users,
                   avg(extract(epoch FROM ended_at - started_at) * 1000) AS avg_ms,
                   max(started_at) AS last_run
            FROM runs r
            WHERE r.started_at >= ? AND r.started_at < ?
              AND EXISTS (SELECT 1 FROM jsonb_array_elements(r.screens) sc WHERE sc->>'flowId' = ANY(?))
            """.trimIndent(),
            from, until, ids, map = ::toUsage,
        ).first()
    }

    private fun toUsage(rs: ResultSet) = FlowUsage(
        runs = rs.getInt("runs"),
        completed = rs.getInt("completed"),
        stopped = rs.getInt("stopped"),
        failed = rs.getInt("failed"),
        users = rs.getInt("users"),
        avgDurationMillis = rs.getObject("avg_ms")?.let { (it as Number).toLong() },
        lastRunAt = rs.getTimestamp("last_run")?.toInstant(),
    )

    /** One entry per day from [since] to today (in [zone]), zero-filled. */
    private fun fillDays(rows: List<Triple<LocalDate, String, Int>>, since: Instant, zone: String): List<DailyRuns> {
        val z = ZoneId.of(zone)
        val byDay = rows.groupBy { it.first }
        val first = since.atZone(z).toLocalDate()
        val last = maxOf(Instant.now().atZone(z).toLocalDate(), rows.maxOfOrNull { it.first } ?: first)
        return generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }.map { day ->
            DailyRuns(day, byDay[day].orEmpty().mapNotNull { r -> runCatching { RunStatus.valueOf(r.second) }.getOrNull()?.let { it to r.third } }.toMap())
        }.toList()
    }
}

class JdbcCommentRepository(private val db: Database) : CommentRepository {
    private val select = """
        SELECT c.*, u.name AS author_name, u.email AS author_email FROM flow_comments c LEFT JOIN users u ON u.id = c.user_id
    """.trimIndent()

    override suspend fun create(comment: FlowComment): FlowComment = db.tx {
        update(
            "INSERT INTO flow_comments (id, flow_id, user_id, step_id, body, created_at) VALUES (?, ?, ?, ?, ?, ?)",
            comment.id, comment.flowId, comment.userId, comment.stepId, comment.body, comment.createdAt,
        )
        findIn(comment.id)!!
    }

    override suspend fun list(flowId: UUID): List<FlowComment> = db.tx {
        query("$select WHERE c.flow_id = ? ORDER BY c.created_at", flowId, map = ::toComment)
    }

    override suspend fun find(id: UUID): FlowComment? = db.tx { findIn(id) }

    override suspend fun edit(id: UUID, body: String, at: Instant): FlowComment? = db.tx {
        if (update("UPDATE flow_comments SET body = ?, edited_at = ? WHERE id = ?", body, at, id) == 0) null else findIn(id)
    }

    override suspend fun resolve(id: UUID, resolvedBy: UUID?, at: Instant?): FlowComment? = db.tx {
        if (update("UPDATE flow_comments SET resolved_at = ?, resolved_by = ? WHERE id = ?", at, resolvedBy, id) == 0) null else findIn(id)
    }

    override suspend fun delete(id: UUID): Boolean = db.tx { update("DELETE FROM flow_comments WHERE id = ?", id) > 0 }

    private fun Connection.findIn(id: UUID): FlowComment? = query("$select WHERE c.id = ?", id, map = ::toComment).firstOrNull()

    private fun toComment(rs: ResultSet) = FlowComment(
        id = rs.uuid("id"),
        flowId = rs.uuid("flow_id"),
        userId = rs.getObject("user_id", UUID::class.java),
        authorName = rs.getString("author_name"),
        authorEmail = rs.getString("author_email"),
        stepId = rs.getString("step_id"),
        body = rs.getString("body"),
        createdAt = rs.instant("created_at"),
        editedAt = rs.getTimestamp("edited_at")?.toInstant(),
        resolvedAt = rs.getTimestamp("resolved_at")?.toInstant(),
    )
}

class JdbcLayoutRepository(private val db: Database) : LayoutRepository {
    @Serializable private data class Pos(val x: Double, val y: Double)

    private val serializer = MapSerializer(String.serializer(), Pos.serializer())

    override suspend fun get(flowId: UUID): Map<String, NodePosition>? = db.tx {
        query("SELECT positions FROM flow_layouts WHERE flow_id = ?", flowId) { rs ->
            Json.decodeFromString(serializer, rs.getString("positions")).mapValues { NodePosition(it.value.x, it.value.y) }
        }.firstOrNull()
    }

    override suspend fun save(flowId: UUID, positions: Map<String, NodePosition>, userId: UUID, at: Instant) {
        db.tx {
            update(
                """
                INSERT INTO flow_layouts (flow_id, positions, updated_by, updated_at) VALUES (?, ?::jsonb, ?, ?)
                ON CONFLICT (flow_id) DO UPDATE SET positions = excluded.positions, updated_by = excluded.updated_by, updated_at = excluded.updated_at
                """.trimIndent(),
                flowId, Json.encodeToString(serializer, positions.mapValues { Pos(it.value.x, it.value.y) }), userId, at,
            )
        }
    }
}
