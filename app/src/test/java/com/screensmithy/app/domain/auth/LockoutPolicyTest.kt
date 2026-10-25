package com.screensmithy.app.domain.auth

import org.junit.Assert.assertEquals
import org.junit.Test

class LockoutPolicyTest {

    @Test
    fun `calculateLockoutDurationMs enforces progressive delay scaling`() {
        assertEquals(0L, LockoutPolicy.calculateLockoutDurationMs(0))
        assertEquals(0L, LockoutPolicy.calculateLockoutDurationMs(1))
        assertEquals(0L, LockoutPolicy.calculateLockoutDurationMs(2))

        // 3 failed attempts: 30s
        assertEquals(30_000L, LockoutPolicy.calculateLockoutDurationMs(3))

        // 4 failed attempts: 60s
        assertEquals(60_000L, LockoutPolicy.calculateLockoutDurationMs(4))

        // 5 failed attempts: 2 minutes
        assertEquals(120_000L, LockoutPolicy.calculateLockoutDurationMs(5))

        // 6 failed attempts: 5 minutes
        assertEquals(300_000L, LockoutPolicy.calculateLockoutDurationMs(6))

        // 7+ failed attempts: 15 minutes (capped)
        assertEquals(900_000L, LockoutPolicy.calculateLockoutDurationMs(7))
        assertEquals(900_000L, LockoutPolicy.calculateLockoutDurationMs(15))
        assertEquals(900_000L, LockoutPolicy.calculateLockoutDurationMs(100))
    }
}
