package com.screensmithy.app.domain.auth

/**
 * Snapshot of current rate-limiting lockout status.
 *
 * @property isLockedOut True if authentication is currently throttled.
 * @property remainingSeconds Seconds remaining before the lockout window expires.
 * @property failedAttempts Count of consecutive failed authentication attempts.
 */
data class LockoutState(
    val isLockedOut: Boolean,
    val remainingSeconds: Long = 0L,
    val failedAttempts: Int = 0
)

/**
 * Progressive rate-limiting backoff policy for failed admin PIN attempts.
 * Prevents rapid brute-forcing while avoiding permanent unrecoverable lockout.
 */
object LockoutPolicy {

    /**
     * Calculates the lockout penalty duration in milliseconds for the given [failedAttempts] count.
     */
    fun calculateLockoutDurationMs(failedAttempts: Int): Long {
        return when {
            failedAttempts < 3 -> 0L
            failedAttempts == 3 -> 30_000L       // 30 seconds
            failedAttempts == 4 -> 60_000L       // 1 minute
            failedAttempts == 5 -> 120_000L      // 2 minutes
            failedAttempts == 6 -> 300_000L      // 5 minutes
            else -> 900_000L                    // 15 minutes maximum penalty
        }
    }
}

/**
 * Repository interface for managing admin PIN credentials and persisted rate-limiting state.
 * Plaintext PINs are never stored; only salted PBKDF2 hashes are persisted.
 */
interface AdminAuthRepository {

    /**
     * Retrieves the stored [HashedPin], or null if no PIN has been set up yet.
     */
    suspend fun getHashedPin(): HashedPin?

    /**
     * Checks whether an admin PIN has been configured.
     */
    suspend fun isPinConfigured(): Boolean

    /**
     * Securely saves the [hashedPin]. Resets failed attempt counters.
     */
    suspend fun savePin(hashedPin: HashedPin)

    /**
     * Clears stored PIN material (used for administrative resets within an authenticated session).
     */
    suspend fun clearPin()

    /**
     * Retrieves current consecutive failed attempt count.
     */
    suspend fun getFailedAttempts(): Int

    /**
     * Records a failed attempt at [nowEpochMs], increments the counter, calculates
     * progressive lockout delay, persists the lockout deadline, and returns the updated [LockoutState].
     */
    suspend fun recordFailedAttempt(nowEpochMs: Long = System.currentTimeMillis()): LockoutState

    /**
     * Resets failed attempt counter and lockout deadline upon successful authentication.
     */
    suspend fun resetFailedAttempts()

    /**
     * Evaluates whether authentication is currently locked out at [nowEpochMs].
     */
    suspend fun getLockoutState(nowEpochMs: Long = System.currentTimeMillis()): LockoutState
}
