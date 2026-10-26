package com.screensmithy.app.domain.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WifiTransferSessionTest {

    @Test
    fun generateSecurePairingCode_generatesSixDigitCode() {
        for (i in 1..100) {
            val code = WifiTransferSession.generateSecurePairingCode()
            assertEquals(6, code.length)
            val num = code.toIntOrNull()
            assertTrue(num != null && num in 100_000..999_999)
        }
    }

    @Test
    fun url_formatsCorrectlyWithIpAndPort() {
        val session = WifiTransferSession(
            ipAddress = "192.168.1.50",
            port = 8080
        )
        assertEquals("http://192.168.1.50:8080/", session.url)
    }

    @Test
    fun isExpired_whenBeforeExpiry_returnsFalse() {
        val session = WifiTransferSession(
            ipAddress = "192.168.1.50",
            port = 8080,
            expiresAtEpochMs = System.currentTimeMillis() + 60_000L
        )
        assertFalse(session.isExpired)
        assertTrue(session.remainingSeconds > 0)
    }

    @Test
    fun isExpired_whenPastExpiry_returnsTrue() {
        val session = WifiTransferSession(
            ipAddress = "192.168.1.50",
            port = 8080,
            expiresAtEpochMs = System.currentTimeMillis() - 1000L
        )
        assertTrue(session.isExpired)
        assertEquals(0L, session.remainingSeconds)
    }

    @Test
    fun isLockedOut_afterMaxFailedAttempts_returnsTrue() {
        val session = WifiTransferSession(
            ipAddress = "192.168.1.50",
            port = 8080,
            failedPairingAttempts = WifiTransferSession.MAX_FAILED_ATTEMPTS
        )
        assertTrue(session.isLockedOut)
    }

    @Test
    fun isLockedOut_underMaxFailedAttempts_returnsFalse() {
        val session = WifiTransferSession(
            ipAddress = "192.168.1.50",
            port = 8080,
            failedPairingAttempts = 3
        )
        assertFalse(session.isLockedOut)
    }

    @Test
    fun sessionState_transitionsCorrectly() {
        val initial = WifiTransferSession(ipAddress = "192.168.1.50", port = 8080)
        assertEquals(TransferSessionState.WaitingForConnection, initial.state)

        val receiving = initial.copy(
            state = TransferSessionState.ReceivingUpload(
                fileName = "test.html",
                bytesRead = 1024L,
                totalBytes = 2048L,
                progressPercent = 50
            )
        )
        assertTrue(receiving.state is TransferSessionState.ReceivingUpload)

        val completed = initial.copy(
            state = TransferSessionState.Completed(
                fileName = "test.html",
                sizeBytes = 2048L,
                stagedFile = File("/tmp/test.html")
            )
        )
        assertTrue(completed.state is TransferSessionState.Completed)
    }
}
