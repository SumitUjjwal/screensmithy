package com.screensmithy.app.domain.recovery

import com.screensmithy.app.domain.model.RetryPolicy
import kotlin.random.Random

/**
 * Calculates bounded exponential backoff delays with random jitter for kiosk content recovery.
 *
 * Algorithm:
 * - Delay = min(initialBackoff * 2^(attempt - 1), maxBackoff) * jitter(0.85 .. 1.15)
 * - Clamped within [initialBackoff, maxBackoff]
 * - Caps at [RetryPolicy.maxRetries]. Returns `null` when retries are exhausted.
 */
class BackoffCalculator(
    private val randomFractionProvider: () -> Double = { Random.nextDouble() }
) {

    /**
     * Computes the backoff delay in milliseconds for the specified [attemptNumber] (1-indexed).
     *
     * @return Delay in milliseconds, or `null` if [attemptNumber] exceeds [RetryPolicy.maxRetries].
     */
    fun calculateDelayMs(retryPolicy: RetryPolicy, attemptNumber: Int): Long? {
        if (attemptNumber <= 0 || attemptNumber > retryPolicy.maxRetries) {
            return null
        }

        val exponent = (attemptNumber - 1).coerceAtMost(30)
        val multiplier = 1L shl exponent
        val initialMs = retryPolicy.initialBackoffSeconds * 1000L
        val maxMs = retryPolicy.maxBackoffSeconds * 1000L

        val unclippedBaseMs = try {
            Math.multiplyExact(initialMs, multiplier)
        } catch (_: ArithmeticException) {
            maxMs
        }
        val baseMs = minOf(unclippedBaseMs, maxMs)

        // Apply +/- 15% random jitter to avoid thundering herd on network restoration
        val jitterFactor = 0.85 + (0.30 * randomFractionProvider().coerceIn(0.0, 1.0))
        val delayWithJitter = (baseMs * jitterFactor).toLong()

        return delayWithJitter.coerceIn(1_000L, maxMs)
    }

    /**
     * Determines whether another retry attempt is permitted.
     */
    fun hasRemainingRetries(retryPolicy: RetryPolicy, currentAttempt: Int): Boolean {
        return currentAttempt < retryPolicy.maxRetries
    }
}
