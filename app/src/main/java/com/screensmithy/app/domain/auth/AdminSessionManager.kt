package com.screensmithy.app.domain.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface AuthResult {
    data object Success : AuthResult
    data class LockedOut(val remainingSeconds: Long) : AuthResult
    data class InvalidPin(val failedAttempts: Int) : AuthResult
    data object PinNotConfigured : AuthResult
}

/**
 * Manages the authenticated admin session lifecycle.
 *
 * Enforces session constraints:
 * - Constant-time PIN verification against stored PBKDF2 hash.
 * - Rate limiting check before verification; increments attempts and persists lockout on mismatch.
 * - Enforces session expiration after inactivity ([SESSION_TIMEOUT_MS], 5 minutes default).
 * - Automatic session revocation on logout, returning to kiosk, cancellation, or backgrounding.
 * - No developer backdoor or universal master PIN.
 */
class AdminSessionManager(
    private val authRepository: AdminAuthRepository,
    private val sessionTimeoutMs: Long = DEFAULT_SESSION_TIMEOUT_MS
) {

    private val _isAuthenticated = MutableStateFlow(false)
    val isAuthenticated: StateFlow<Boolean> = _isAuthenticated.asStateFlow()

    private var lastActivityEpochMs: Long = 0L

    /**
     * Attempts admin authentication with [candidatePin].
     */
    suspend fun authenticate(
        candidatePin: CharArray,
        nowEpochMs: Long = System.currentTimeMillis()
    ): AuthResult {
        // 1. Check active lockout
        val lockoutState = authRepository.getLockoutState(nowEpochMs)
        if (lockoutState.isLockedOut) {
            return AuthResult.LockedOut(lockoutState.remainingSeconds)
        }

        // 2. Fetch stored credentials
        val storedHash = authRepository.getHashedPin() ?: return AuthResult.PinNotConfigured

        // 3. Constant-time verification
        val isMatch = PinSecurity.verifyPin(candidatePin, storedHash)

        return if (isMatch) {
            // Authentication successful: reset rate-limiting penalty and activate session
            authRepository.resetFailedAttempts()
            _isAuthenticated.value = true
            lastActivityEpochMs = nowEpochMs
            AuthResult.Success
        } else {
            // Authentication failed: record failure and update persistent lockout
            val updatedLockout = authRepository.recordFailedAttempt(nowEpochMs)
            _isAuthenticated.value = false
            if (updatedLockout.isLockedOut) {
                AuthResult.LockedOut(updatedLockout.remainingSeconds)
            } else {
                AuthResult.InvalidPin(updatedLockout.failedAttempts)
            }
        }
    }

    /**
     * Checks whether an active authenticated session remains valid within the inactivity window.
     */
    fun isSessionValid(nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        if (!_isAuthenticated.value) return false
        val isExpired = (nowEpochMs - lastActivityEpochMs) > sessionTimeoutMs
        if (isExpired) {
            endSession()
            return false
        }
        return true
    }

    /**
     * Extends the active session's inactivity deadline.
     */
    fun extendSession(nowEpochMs: Long = System.currentTimeMillis()) {
        if (_isAuthenticated.value) {
            lastActivityEpochMs = nowEpochMs
        }
    }

    /**
     * Explicitly revokes the authenticated session (on logout, return to kiosk, or backgrounding).
     */
    fun endSession() {
        _isAuthenticated.value = false
        lastActivityEpochMs = 0L
    }

    companion object {
        /**
         * 5-minute inactivity session timeout.
         */
        const val DEFAULT_SESSION_TIMEOUT_MS: Long = 5 * 60 * 1000L
    }
}
