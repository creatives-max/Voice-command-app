package com.voicecontrol.core.common

import com.voicecontrol.core.common.lock.AppLockPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppLockPolicyTest {
    @Test
    fun `locks on start and after the timeout in the background`() {
        assertFalse(AppLockPolicy.shouldLock(false, false, null, 0, 0))
        assertTrue(AppLockPolicy.shouldLock(true, false, null, 0, 60))
        assertFalse(AppLockPolicy.shouldLock(true, true, null, 10_000, 60))
        assertFalse(AppLockPolicy.shouldLock(true, true, 0, 59_999, 60))
        assertTrue(AppLockPolicy.shouldLock(true, true, 0, 60_000, 60))
        assertTrue(AppLockPolicy.shouldLock(true, true, 5_000, 5_000, 0))
        assertEquals("After 5 minutes", AppLockPolicy.describe(300))
        assertEquals("Immediately", AppLockPolicy.describe(0))
    }
}
