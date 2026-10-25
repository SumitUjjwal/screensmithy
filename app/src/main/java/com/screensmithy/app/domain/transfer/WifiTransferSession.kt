package com.screensmithy.app.domain.transfer

import java.io.File
import java.security.SecureRandom
import java.util.UUID

/**
 * Observable lifecycle state of an active Wi-Fi transfer session.
 */
sealed interface TransferSessionState {
    data object WaitingForConnection : TransferSessionState

    data class ReceivingUpload(
        val fileName: String,
        val bytesRead: Long,
        val totalBytes: Long,
        val progressPercent: Int
    ) : TransferSessionState

    data class Validating(val fileName: String) : TransferSessionState

    data class Completed(
        val fileName: String,
        val sizeBytes: Long,
        val stagedFile: File
    ) : TransferSessionState

    data class Failed(val reason: String) : TransferSessionState

    data object Cancelled : TransferSessionState

    data object Expired : TransferSessionState
}

/**
 * Immutable session model representing a temporary Wi-Fi upload session on the TV.
 */
data class WifiTransferSession(
    val sessionId: String = UUID.randomUUID().toString(),
    val pairingCode: String = generateSecurePairingCode(),
    val ipAddress: String,
    val port: Int,
    val expiresAtEpochMs: Long = System.currentTimeMillis() + DEFAULT_TIMEOUT_MS,
    val state: TransferSessionState = TransferSessionState.WaitingForConnection,
    val failedPairingAttempts: Int = 0
) {
    val url: String get() = "http://$ipAddress:$port/"

    val isExpired: Boolean get() = System.currentTimeMillis() >= expiresAtEpochMs

    val isLockedOut: Boolean get() = failedPairingAttempts >= MAX_FAILED_ATTEMPTS

    val remainingSeconds: Long
        get() = maxOf(0L, (expiresAtEpochMs - System.currentTimeMillis()) / 1000L)

    companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 300_000L // 5 minutes
        const val MAX_FAILED_ATTEMPTS: Int = 5

        /**
         * Generates a 6-digit cryptographically secure random pairing code.
         */
        fun generateSecurePairingCode(): String {
            val random = SecureRandom()
            val number = 100_000 + random.nextInt(900_000)
            return number.toString()
        }
    }
}
