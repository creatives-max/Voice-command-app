package com.voicecontrol.core.common.lock

/**
 * When the app must ask for the fingerprint, face or screen lock again: on start, and after it was in
 * the background for at least [timeoutSeconds] (0 = every time it returns).
 */
object AppLockPolicy {
    /** Choices offered in Settings, in seconds. */
    val TIMEOUTS = listOf(0, 60, 300, 1800)

    fun shouldLock(enabled: Boolean, unlockedAtLeastOnce: Boolean, backgroundSinceMillis: Long?, nowMillis: Long, timeoutSeconds: Int): Boolean {
        if (!enabled) return false
        if (!unlockedAtLeastOnce) return true
        val since = backgroundSinceMillis ?: return false
        return nowMillis - since >= timeoutSeconds.coerceAtLeast(0) * 1000L
    }

    fun describe(timeoutSeconds: Int): String = when {
        timeoutSeconds <= 0 -> "Immediately"
        timeoutSeconds < 60 -> "After $timeoutSeconds seconds"
        timeoutSeconds < 3600 -> "After ${timeoutSeconds / 60} minute${if (timeoutSeconds / 60 == 1) "" else "s"}"
        else -> "After ${timeoutSeconds / 3600} hour${if (timeoutSeconds / 3600 == 1) "" else "s"}"
    }
}
