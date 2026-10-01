package com.voicecontrol.domain.account

import java.time.Instant
import java.util.UUID

/** Everything stored about a user, and erasing it (GDPR access and erasure). */
interface AccountDataRepository {
    /** All of the user's data as one JSON document; secrets (password and key hashes, webhook secrets) are left out. */
    suspend fun export(userId: UUID): String

    /** Organizations where the user is the only admin while other members remain (deletion must wait). */
    suspend fun orgsBlockingDeletion(userId: UUID): List<String>

    /**
     * Deletes the account and its data: organizations where the user is the only member are deleted, the
     * user's organization flows are handed to another admin of that organization, comments are erased.
     */
    suspend fun delete(userId: UUID)
}

data class CrashReport(
    val id: UUID,
    val userId: UUID?,
    val installId: String?,
    val fingerprint: String,
    val exception: String,
    val message: String?,
    val stacktrace: String,
    val thread: String?,
    val appVersion: String?,
    val androidSdk: Int?,
    val deviceModel: String?,
    val occurredAt: Instant,
)

/** Reports grouped by fingerprint. */
data class CrashGroup(
    val fingerprint: String,
    val exception: String,
    val message: String?,
    val topFrame: String?,
    val count: Int,
    val firstSeen: Instant,
    val lastSeen: Instant,
    val appVersions: List<String>,
    val latestStacktrace: String,
)

interface CrashRepository {
    suspend fun insert(reports: List<CrashReport>): Int
    suspend fun groups(userId: UUID, limit: Int): List<CrashGroup>
}
