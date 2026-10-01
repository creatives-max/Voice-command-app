package com.voicecontrol.core.model.diagnostics

import kotlinx.serialization.Serializable

/** One crash, as stored on the phone until it is uploaded (only with crash reports turned on). */
@Serializable
data class CrashRecord(
    val id: String,
    val exception: String,
    val message: String? = null,
    val stacktrace: String,
    val thread: String? = null,
    val appVersion: String? = null,
    val androidSdk: Int? = null,
    val deviceModel: String? = null,
    val occurredAtMillis: Long,
)

/** Turns a throwable into a [CrashRecord] without personal data. */
object CrashRecords {
    private val EMAIL = Regex("[\\w.%+-]+@[\\w.-]+\\.[A-Za-z]{2,}")
    private val DIGITS = Regex("\\d{4,}")
    private const val MAX_STACK = 16_000
    private const val MAX_CAUSES = 5

    /** Removes email addresses and long digit runs (phone numbers, OTPs, card numbers). */
    fun scrub(text: String): String = text.replace(EMAIL, "<email>").replace(DIGITS, "<digits>")

    fun from(
        error: Throwable,
        thread: String?,
        appVersion: String?,
        androidSdk: Int?,
        deviceModel: String?,
        nowMillis: Long,
        id: String,
    ): CrashRecord = CrashRecord(
        id = id,
        exception = error::class.java.name,
        message = error.message?.let(::scrub)?.take(500),
        stacktrace = scrub(stackOf(error)).take(MAX_STACK),
        thread = thread?.take(100),
        appVersion = appVersion,
        androidSdk = androidSdk,
        deviceModel = deviceModel,
        occurredAtMillis = nowMillis,
    )

    /** "Type: message" then frames, then "Caused by:" chains (bounded). */
    fun stackOf(error: Throwable): String = buildString {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < MAX_CAUSES) {
            if (depth > 0) append("Caused by: ")
            append(current::class.java.name)
            current.message?.let { append(": ").append(it) }
            append('\n')
            current.stackTrace.take(60).forEach { append("    at ").append(it.toString()).append('\n') }
            current = current.cause?.takeIf { it !== current }
            depth++
        }
    }
}
