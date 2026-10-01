package com.voicecontrol.application.account

import com.voicecontrol.domain.account.AccountDataRepository
import com.voicecontrol.domain.account.CrashGroup
import com.voicecontrol.domain.account.CrashReport
import com.voicecontrol.domain.account.CrashRepository
import com.voicecontrol.domain.common.DomainException
import com.voicecontrol.domain.user.PasswordHasher
import com.voicecontrol.domain.user.SessionStore
import com.voicecontrol.domain.user.UserRepository
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Data export and account deletion. */
class AccountService(
    private val data: AccountDataRepository,
    private val users: UserRepository,
    private val hasher: PasswordHasher,
    private val sessions: SessionStore,
    private val onDeleted: suspend (UUID) -> Unit = {},
) {
    suspend fun export(userId: UUID): String {
        users.findById(userId) ?: throw DomainException.NotFound("User not found")
        return data.export(userId)
    }

    /** Deletes the account after re-checking the password; signs out every device. */
    suspend fun delete(userId: UUID, password: String) {
        val user = users.findById(userId) ?: throw DomainException.NotFound("User not found")
        if (!hasher.verify(password, user.passwordHash)) throw DomainException.Forbidden("Password is not correct")
        val blocking = data.orgsBlockingDeletion(userId)
        if (blocking.isNotEmpty()) {
            throw DomainException.Conflict(
                "You are the only admin of ${blocking.joinToString(", ")}. Make someone else admin or delete the organization first.",
            )
        }
        sessions.revokeAll(userId)
        data.delete(userId)
        onDeleted(userId)
    }
}

/** What the phone sends for one crash. */
data class CrashInput(
    val exception: String,
    val message: String?,
    val stacktrace: String,
    val thread: String?,
    val appVersion: String?,
    val androidSdk: Int?,
    val deviceModel: String?,
    val occurredAtMillis: Long,
    val installId: String?,
)

/** Self-hosted crash reporting: scrubbed, fingerprinted, grouped. */
class CrashService(private val repo: CrashRepository, private val clock: Clock = Clock.systemUTC()) {
    suspend fun report(userId: UUID?, inputs: List<CrashInput>): Int {
        if (inputs.isEmpty()) return 0
        if (inputs.size > MAX_BATCH) throw DomainException.Validation("At most $MAX_BATCH reports per upload")
        val now = clock.instant()
        val reports = inputs.map { i ->
            val exception = i.exception.trim().take(200).ifEmpty { throw DomainException.Validation("exception is required") }
            val stack = scrub(i.stacktrace).take(MAX_STACK).ifEmpty { throw DomainException.Validation("stacktrace is required") }
            val at = Instant.ofEpochMilli(i.occurredAtMillis).takeIf { !it.isAfter(now.plus(Duration.ofMinutes(5))) && it.isAfter(now.minus(Duration.ofDays(90))) } ?: now
            CrashReport(
                UUID.randomUUID(), userId, i.installId?.take(64), fingerprint(exception, stack), exception,
                i.message?.let(::scrub)?.take(500), stack, i.thread?.take(100), i.appVersion?.take(40), i.androidSdk, i.deviceModel?.take(80), at,
            )
        }
        return repo.insert(reports)
    }

    suspend fun groups(userId: UUID): List<CrashGroup> = repo.groups(userId, 50)

    companion object {
        const val MAX_BATCH = 20
        const val MAX_STACK = 16_000
        private val EMAIL = Regex("[\\w.%+-]+@[\\w.-]+\\.[A-Za-z]{2,}")
        private val DIGITS = Regex("\\d{4,}")

        /** Removes emails and long digit runs (phone numbers, OTPs, card numbers) from free text. */
        fun scrub(text: String): String = text.replace(EMAIL, "<email>").replace(DIGITS, "<digits>")

        /** Exception type + first frames from our own code (or the first frames), without line numbers. */
        fun fingerprint(exception: String, stack: String): String {
            val frames = stack.lines().map { it.trim() }.filter { it.startsWith("at ") }
            val ours = frames.filter { it.contains("com.voicecontrol") }.ifEmpty { frames }.take(3)
                .map { it.substringBefore('(') }
            val key = exception + "|" + ours.joinToString("|")
            return MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
        }
    }
}
