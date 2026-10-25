package com.screensmithy.app.domain.auth

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AdminSessionManagerTest {

    private val authRepository = mockk<AdminAuthRepository>(relaxed = true)
    private lateinit var sessionManager: AdminSessionManager

    private val validPin = "654321".toCharArray()
    private val hashedPin = PinSecurity.hashPin(validPin)

    @Before
    fun setUp() {
        sessionManager = AdminSessionManager(
            authRepository = authRepository,
            sessionTimeoutMs = 300_000L // 5 minutes
        )
    }

    @Test
    fun `authenticate fails fast if repository reports active lockout`() = runTest {
        coEvery { authRepository.getLockoutState(any()) } returns LockoutState(
            isLockedOut = true,
            remainingSeconds = 25L,
            failedAttempts = 3
        )

        val result = sessionManager.authenticate("123456".toCharArray(), nowEpochMs = 1000L)

        assertTrue(result is AuthResult.LockedOut)
        assertEquals(25L, (result as AuthResult.LockedOut).remainingSeconds)
        assertFalse(sessionManager.isAuthenticated.value)
        // Ensure no verification or attempt recording occurred
        coVerify(exactly = 0) { authRepository.recordFailedAttempt(any()) }
    }

    @Test
    fun `authenticate returns PinNotConfigured when no stored hash exists`() = runTest {
        coEvery { authRepository.getLockoutState(any()) } returns LockoutState(isLockedOut = false)
        coEvery { authRepository.getHashedPin() } returns null

        val result = sessionManager.authenticate("123456".toCharArray())

        assertEquals(AuthResult.PinNotConfigured, result)
        assertFalse(sessionManager.isAuthenticated.value)
    }

    @Test
    fun `authenticate with matching PIN succeeds, sets isAuthenticated, and resets failed attempts`() = runTest {
        coEvery { authRepository.getLockoutState(any()) } returns LockoutState(isLockedOut = false)
        coEvery { authRepository.getHashedPin() } returns hashedPin

        val result = sessionManager.authenticate(validPin, nowEpochMs = 1000L)

        assertEquals(AuthResult.Success, result)
        assertTrue(sessionManager.isAuthenticated.value)
        coVerify(exactly = 1) { authRepository.resetFailedAttempts() }
    }

    @Test
    fun `authenticate with wrong PIN fails, updates attempt count, and remains unauthenticated`() = runTest {
        coEvery { authRepository.getLockoutState(any()) } returns LockoutState(isLockedOut = false)
        coEvery { authRepository.getHashedPin() } returns hashedPin
        coEvery { authRepository.recordFailedAttempt(any()) } returns LockoutState(
            isLockedOut = false,
            remainingSeconds = 0L,
            failedAttempts = 1
        )

        val result = sessionManager.authenticate("111111".toCharArray(), nowEpochMs = 1000L)

        assertTrue(result is AuthResult.InvalidPin)
        assertEquals(1, (result as AuthResult.InvalidPin).failedAttempts)
        assertFalse(sessionManager.isAuthenticated.value)
        coVerify(exactly = 1) { authRepository.recordFailedAttempt(1000L) }
    }

    @Test
    fun `authenticate with wrong PIN triggering lockout returns LockedOut result`() = runTest {
        coEvery { authRepository.getLockoutState(any()) } returns LockoutState(isLockedOut = false)
        coEvery { authRepository.getHashedPin() } returns hashedPin
        coEvery { authRepository.recordFailedAttempt(any()) } returns LockoutState(
            isLockedOut = true,
            remainingSeconds = 30L,
            failedAttempts = 3
        )

        val result = sessionManager.authenticate("111111".toCharArray(), nowEpochMs = 1000L)

        assertTrue(result is AuthResult.LockedOut)
        assertEquals(30L, (result as AuthResult.LockedOut).remainingSeconds)
        assertFalse(sessionManager.isAuthenticated.value)
    }

    @Test
    fun `endSession immediately revokes authentication`() = runTest {
        coEvery { authRepository.getLockoutState(any()) } returns LockoutState(isLockedOut = false)
        coEvery { authRepository.getHashedPin() } returns hashedPin

        sessionManager.authenticate(validPin, nowEpochMs = 1000L)
        assertTrue(sessionManager.isAuthenticated.value)

        sessionManager.endSession()

        assertFalse(sessionManager.isAuthenticated.value)
        assertFalse(sessionManager.isSessionValid(nowEpochMs = 1000L))
    }

    @Test
    fun `isSessionValid enforces inactivity expiration`() = runTest {
        val loginTime = 10_000L
        coEvery { authRepository.getLockoutState(any()) } returns LockoutState(isLockedOut = false)
        coEvery { authRepository.getHashedPin() } returns hashedPin

        sessionManager.authenticate(validPin, nowEpochMs = loginTime)
        assertTrue(sessionManager.isSessionValid(nowEpochMs = loginTime + 60_000L)) // 1 min later: valid

        // 5 minutes + 1 ms later: expired!
        val expiredTime = loginTime + 300_001L
        assertFalse(sessionManager.isSessionValid(nowEpochMs = expiredTime))
        assertFalse(sessionManager.isAuthenticated.value)
    }

    @Test
    fun `extendSession postpones inactivity expiration`() = runTest {
        val loginTime = 10_000L
        coEvery { authRepository.getLockoutState(any()) } returns LockoutState(isLockedOut = false)
        coEvery { authRepository.getHashedPin() } returns hashedPin

        sessionManager.authenticate(validPin, nowEpochMs = loginTime)

        // Activity occurs at 4 minutes
        val activeTime = loginTime + 240_000L
        sessionManager.extendSession(nowEpochMs = activeTime)

        // Check at 6 minutes from original login (2 minutes from last activity): still valid!
        val checkTime = loginTime + 360_000L
        assertTrue(sessionManager.isSessionValid(nowEpochMs = checkTime))
    }
}
