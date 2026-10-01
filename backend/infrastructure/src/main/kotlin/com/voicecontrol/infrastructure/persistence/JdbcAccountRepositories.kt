package com.voicecontrol.infrastructure.persistence

import com.voicecontrol.domain.account.AccountDataRepository
import com.voicecontrol.domain.account.CrashGroup
import com.voicecontrol.domain.account.CrashReport
import com.voicecontrol.domain.account.CrashRepository
import java.util.UUID

class JdbcAccountDataRepository(private val db: Database) : AccountDataRepository {

    /** One section of the export: rows of [sql] (a SELECT with one `?` for the user id, possibly repeated) as a JSON array. */
    private fun section(sql: String, params: Int = 1) = "(SELECT coalesce(json_agg(row_to_json(t)), '[]'::json) FROM ($sql) t)" to params

    override suspend fun export(userId: UUID): String = db.tx {
        val sections = linkedMapOf(
            "account" to section("SELECT id, email, name, created_at FROM users WHERE id = ?"),
            "profile" to section("SELECT full_name, email, phone, address_line, city, state, pincode, date_of_birth, updated_at FROM profiles WHERE user_id = ?"),
            "flows" to section(
                """
                SELECT f.id, f.app_package, f.name, f.screen_signature, f.current_version, f.org_id, f.created_at, f.updated_at, f.deleted_at,
                       (SELECT json_agg(json_build_object('version', v.version, 'steps', v.steps, 'source', v.source, 'changeNote', v.change_note, 'createdAt', v.created_at) ORDER BY v.version)
                        FROM flow_versions v WHERE v.flow_id = f.id) AS versions
                FROM flows f WHERE f.user_id = ?
                """.trimIndent(),
            ),
            "history" to section("SELECT id, app_package, started_at, ended_at, status, language, filled_count, step_count, screens FROM runs WHERE user_id = ? ORDER BY started_at"),
            "devices" to section("SELECT id, name, platform, app_version, remote_runs, last_seen_at, created_at FROM devices WHERE user_id = ?"),
            "triggers" to section("SELECT id, flow_id, type, enabled, cron, timezone, device_id, phrase, next_run_at, last_run_at, created_at FROM flow_triggers WHERE user_id = ?"),
            "remoteRuns" to section(
                """
                SELECT r.id, r.flow_id, r.flow_name, r.app_package, r.device_id, r.source, r.status, r.created_at,
                       (SELECT json_agg(json_build_object('at', e.at, 'kind', e.kind, 'message', e.message) ORDER BY e.id) FROM run_request_events e WHERE e.request_id = r.id) AS log
                FROM run_requests r WHERE r.user_id = ?
                """.trimIndent(),
            ),
            "publishedFlows" to section(
                """
                SELECT p.id, p.name, p.description, p.app_package, p.category, p.tags, p.latest_version, p.install_count, p.created_at, p.updated_at,
                       (SELECT json_agg(json_build_object('version', v.version, 'steps', v.steps, 'changelog', v.changelog, 'createdAt', v.created_at) ORDER BY v.version)
                        FROM published_flow_versions v WHERE v.published_id = p.id) AS versions
                FROM published_flows p WHERE p.owner_id = ?
                """.trimIndent(),
            ),
            "ratings" to section("SELECT published_id, stars, review, updated_at FROM flow_ratings WHERE user_id = ?"),
            "organizations" to section("SELECT o.id, o.name, m.role, m.created_at AS joined_at FROM memberships m JOIN organizations o ON o.id = m.org_id WHERE m.user_id = ?"),
            "comments" to section("SELECT id, flow_id, step_id, body, created_at, edited_at, resolved_at FROM flow_comments WHERE user_id = ?"),
            "apiKeysCreated" to section("SELECT id, org_id, name, prefix, scopes, rate_limit_per_minute, created_at, last_used_at, revoked_at FROM api_keys WHERE created_by = ?"),
            "auditEntries" to section("SELECT org_id, action, target_type, target_id, details, at FROM audit_logs WHERE actor_user_id = ? ORDER BY at"),
            "crashReports" to section("SELECT id, exception, message, stacktrace, app_version, android_sdk, device_model, occurred_at FROM crash_reports WHERE user_id = ?"),
        )
        val sql = "SELECT json_build_object('exportedAt', now(), " +
            sections.entries.joinToString(", ") { (name, s) -> "'$name', ${s.first}" } + ")::text AS doc"
        val params = sections.values.flatMap { s -> List(s.second) { userId } }.toTypedArray()
        query(sql, *params) { it.getString("doc") }.first()
    }

    override suspend fun orgsBlockingDeletion(userId: UUID): List<String> = db.tx {
        query(
            """
            SELECT o.name FROM organizations o JOIN memberships m ON m.org_id = o.id AND m.user_id = ? AND m.role = 'ADMIN'
            WHERE NOT EXISTS (SELECT 1 FROM memberships a WHERE a.org_id = o.id AND a.role = 'ADMIN' AND a.user_id <> ?)
              AND EXISTS (SELECT 1 FROM memberships x WHERE x.org_id = o.id AND x.user_id <> ?)
            ORDER BY o.name
            """.trimIndent(),
            userId, userId, userId,
        ) { it.getString("name") }
    }

    override suspend fun delete(userId: UUID) {
        db.tx {
            // Organizations nobody else belongs to go with the account.
            update(
                "DELETE FROM organizations o WHERE EXISTS (SELECT 1 FROM memberships m WHERE m.org_id = o.id AND m.user_id = ?) " +
                    "AND NOT EXISTS (SELECT 1 FROM memberships x WHERE x.org_id = o.id AND x.user_id <> ?)",
                userId, userId,
            )
            // Organization flows stay with the organization: hand them to another admin (or member).
            update(
                """
                UPDATE flows f SET user_id = (
                    SELECT m.user_id FROM memberships m WHERE m.org_id = f.org_id AND m.user_id <> ?
                    ORDER BY CASE m.role WHEN 'ADMIN' THEN 0 WHEN 'EDITOR' THEN 1 ELSE 2 END, m.created_at LIMIT 1)
                WHERE f.user_id = ? AND f.org_id IS NOT NULL
                """.trimIndent(),
                userId, userId,
            )
            update("DELETE FROM flow_comments WHERE user_id = ?", userId)
            update("DELETE FROM users WHERE id = ?", userId)
        }
    }
}

class JdbcCrashRepository(private val db: Database) : CrashRepository {
    override suspend fun insert(reports: List<CrashReport>): Int = db.tx {
        reports.sumOf { r ->
            update(
                """
                INSERT INTO crash_reports (id, user_id, install_id, fingerprint, exception, message, stacktrace, thread, app_version, android_sdk, device_model, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                r.id, r.userId, r.installId, r.fingerprint, r.exception, r.message, r.stacktrace, r.thread, r.appVersion, r.androidSdk, r.deviceModel, r.occurredAt,
            )
        }
    }

    override suspend fun groups(userId: UUID, limit: Int): List<CrashGroup> = db.tx {
        query(
            """
            SELECT fingerprint, count(*) AS n, min(occurred_at) AS first_seen, max(occurred_at) AS last_seen,
                   array_agg(DISTINCT app_version) FILTER (WHERE app_version IS NOT NULL) AS versions,
                   (array_agg(exception ORDER BY occurred_at DESC))[1] AS exception,
                   (array_agg(message ORDER BY occurred_at DESC))[1] AS message,
                   (array_agg(stacktrace ORDER BY occurred_at DESC))[1] AS stacktrace
            FROM crash_reports WHERE user_id = ? GROUP BY fingerprint ORDER BY max(occurred_at) DESC LIMIT ?
            """.trimIndent(),
            userId, limit,
        ) { rs ->
            val stack = rs.getString("stacktrace")
            CrashGroup(
                fingerprint = rs.getString("fingerprint"),
                exception = rs.getString("exception"),
                message = rs.getString("message"),
                topFrame = stack.lines().map { it.trim() }.firstOrNull { it.startsWith("at ") }?.removePrefix("at "),
                count = rs.getInt("n"),
                firstSeen = rs.instant("first_seen"),
                lastSeen = rs.instant("last_seen"),
                appVersions = (rs.getArray("versions")?.array as? Array<*>)?.map { it.toString() }.orEmpty(),
                latestStacktrace = stack,
            )
        }
    }
}
