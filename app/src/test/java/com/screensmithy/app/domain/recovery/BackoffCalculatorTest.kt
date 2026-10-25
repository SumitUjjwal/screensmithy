package com.screensmithy.app.domain.recovery

import com.screensmithy.app.domain.model.RetryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackoffCalculatorTest {

    private val defaultPolicy = RetryPolicy(
        maxRetries = 5,
        initialBackoffSeconds = 5,
        maxBackoffSeconds = 60
    )

    @Test
    fun `neutral jitter produces exact exponential sequence clamped at maxBackoff`() {
        // randomFraction = 0.5 => jitterFactor = 0.85 + (0.30 * 0.5) = 1.00 (neutral)
        val calculator = BackoffCalculator(randomFractionProvider = { 0.5 })

        // Attempt 1: 5s * 2^0 = 5s
        assertEquals(5_000L, calculator.calculateDelayMs(defaultPolicy, 1))

        // Attempt 2: 5s * 2^1 = 10s
        assertEquals(10_000L, calculator.calculateDelayMs(defaultPolicy, 2))

        // Attempt 3: 5s * 2^2 = 20s
        assertEquals(20_000L, calculator.calculateDelayMs(defaultPolicy, 3))

        // Attempt 4: 5s * 2^3 = 40s
        assertEquals(40_000L, calculator.calculateDelayMs(defaultPolicy, 4))

        // Attempt 5: 5s * 2^4 = 80s -> capped at maxBackoffSeconds (60s)
        assertEquals(60_000L, calculator.calculateDelayMs(defaultPolicy, 5))
    }

    @Test
    fun `exhausted attempts return null`() {
        val calculator = BackoffCalculator()

        assertNull(calculator.calculateDelayMs(defaultPolicy, 6))
        assertNull(calculator.calculateDelayMs(defaultPolicy, 10))
        assertNull(calculator.calculateDelayMs(defaultPolicy, 0))
        assertNull(calculator.calculateDelayMs(defaultPolicy, -1))
    }

    @Test
    fun `jitter bounds remain within expected 15 percent range`() {
        val minJitterCalculator = BackoffCalculator(randomFractionProvider = { 0.0 }) // factor = 0.85
        val maxJitterCalculator = BackoffCalculator(randomFractionProvider = { 1.0 }) // factor = 1.15

        val delayMin = minJitterCalculator.calculateDelayMs(defaultPolicy, 1)
        val delayMax = maxJitterCalculator.calculateDelayMs(defaultPolicy, 1)

        assertNotNull(delayMin)
        assertNotNull(delayMax)
        assertEquals(4_250L, delayMin) // 5000 * 0.85
        assertEquals(5_750L, delayMax) // 5000 * 1.15
    }

    @Test
    fun `max delay with max jitter is capped at maxBackoffSeconds`() {
        val maxJitterCalculator = BackoffCalculator(randomFractionProvider = { 1.0 })
        // Attempt 5 base is 60s -> 60s * 1.15 = 69s, but capped at 60s
        val delay = maxJitterCalculator.calculateDelayMs(defaultPolicy, 5)
        assertEquals(60_000L, delay)
    }

    @Test
    fun `hasRemainingRetries reflects attempt count correctly`() {
        val calculator = BackoffCalculator()

        assertTrue(calculator.hasRemainingRetries(defaultPolicy, 0))
        assertTrue(calculator.hasRemainingRetries(defaultPolicy, 4))
        assertFalse(calculator.hasRemainingRetries(defaultPolicy, 5))
        assertFalse(calculator.hasRemainingRetries(defaultPolicy, 6))
    }
}
