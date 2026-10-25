package com.screensmithy.app.domain.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticLoggerTest {

    @Test
    fun log_storesEntryWithSanitizedContent() {
        val logger = BoundedDiagnosticLogger(maxEntries = 10)
        logger.info(
            category = LogCategory.CONFIG,
            message = "Updated URL to https://kiosk.example.com/?token=secret123",
            details = "Authorization: Bearer mytoken"
        )

        val logs = logger.getRecentLogs()
        assertEquals(1, logs.size)
        val entry = logs.first()
        assertEquals(LogLevel.INFO, entry.level)
        assertEquals(LogCategory.CONFIG, entry.category)
        assertTrue(entry.message.contains("token=REDACTED"))
        assertFalse(entry.message.contains("secret123"))
        assertEquals("Authorization: REDACTED", entry.details)
    }

    @Test
    fun log_whenCapacityExceeded_dropsOldestEntries() {
        val maxEntries = 5
        val logger = BoundedDiagnosticLogger(maxEntries = maxEntries)

        for (i in 1..10) {
            logger.info(LogCategory.STARTUP, "Log entry $i")
        }

        val logs = logger.getRecentLogs()
        assertEquals(5, logs.size)
        // Entries 1..5 should have been rotated out; entries 6..10 should remain
        assertEquals("Log entry 6", logs[0].message)
        assertEquals("Log entry 7", logs[1].message)
        assertEquals("Log entry 8", logs[2].message)
        assertEquals("Log entry 9", logs[3].message)
        assertEquals("Log entry 10", logs[4].message)
    }

    @Test
    fun clear_removesAllEntries() {
        val logger = BoundedDiagnosticLogger(maxEntries = 5)
        logger.warn(LogCategory.NETWORK, "Connection lost")
        logger.error(LogCategory.RENDERER, "Crash detected")

        assertEquals(2, logger.getRecentLogs().size)

        logger.clear()
        assertTrue(logger.getRecentLogs().isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun constructor_withZeroCapacity_throwsException() {
        BoundedDiagnosticLogger(maxEntries = 0)
    }
}
