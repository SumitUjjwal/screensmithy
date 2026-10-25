package com.screensmithy.app.data.auth

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.screensmithy.app.domain.auth.AdminAuthRepository
import com.screensmithy.app.domain.auth.HashedPin
import com.screensmithy.app.domain.auth.LockoutPolicy
import com.screensmithy.app.domain.auth.LockoutState
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import java.io.IOException
import kotlin.math.ceil

val Context.adminAuthDataStore: DataStore<Preferences> by preferencesDataStore(name = "admin_auth_prefs")

/**
 * DataStore-backed repository for persistent administrative security credentials and rate-limiting state.
 * Kept strictly segregated from general configuration settings.
 */
class DataStoreAdminAuthRepository(
    private val dataStore: DataStore<Preferences>
) : AdminAuthRepository {

    override suspend fun getHashedPin(): HashedPin? {
        val prefs = dataStore.data.catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }.first()

        val hash = prefs[KEY_PIN_HASH] ?: return null
        val salt = prefs[KEY_PIN_SALT] ?: return null
        val iterations = prefs[KEY_PIN_ITERATIONS] ?: 100_000

        return try {
            HashedPin(hashHex = hash, saltHex = salt, iterations = iterations)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    override suspend fun isPinConfigured(): Boolean {
        return getHashedPin() != null
    }

    override suspend fun savePin(hashedPin: HashedPin) {
        dataStore.edit { prefs ->
            prefs[KEY_PIN_HASH] = hashedPin.hashHex
            prefs[KEY_PIN_SALT] = hashedPin.saltHex
            prefs[KEY_PIN_ITERATIONS] = hashedPin.iterations
            // Reset rate-limiting penalties upon new PIN registration
            prefs[KEY_FAILED_ATTEMPTS] = 0
            prefs[KEY_LOCKOUT_UNTIL_EPOCH_MS] = 0L
        }
    }

    override suspend fun clearPin() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_PIN_HASH)
            prefs.remove(KEY_PIN_SALT)
            prefs.remove(KEY_PIN_ITERATIONS)
            prefs[KEY_FAILED_ATTEMPTS] = 0
            prefs[KEY_LOCKOUT_UNTIL_EPOCH_MS] = 0L
        }
    }

    override suspend fun getFailedAttempts(): Int {
        val prefs = dataStore.data.catch { emit(emptyPreferences()) }.first()
        return prefs[KEY_FAILED_ATTEMPTS] ?: 0
    }

    override suspend fun recordFailedAttempt(nowEpochMs: Long): LockoutState {
        var currentAttempts = 0
        var newLockoutUntil = 0L
        var penaltyDurationMs = 0L

        dataStore.edit { prefs ->
            val priorAttempts = prefs[KEY_FAILED_ATTEMPTS] ?: 0
            currentAttempts = priorAttempts + 1
            penaltyDurationMs = LockoutPolicy.calculateLockoutDurationMs(currentAttempts)
            newLockoutUntil = if (penaltyDurationMs > 0L) nowEpochMs + penaltyDurationMs else 0L

            prefs[KEY_FAILED_ATTEMPTS] = currentAttempts
            prefs[KEY_LOCKOUT_UNTIL_EPOCH_MS] = newLockoutUntil
        }

        val remainingSec = if (penaltyDurationMs > 0L) {
            ceil(penaltyDurationMs / 1000.0).toLong().coerceAtLeast(1L)
        } else {
            0L
        }

        return LockoutState(
            isLockedOut = penaltyDurationMs > 0L,
            remainingSeconds = remainingSec,
            failedAttempts = currentAttempts
        )
    }

    override suspend fun resetFailedAttempts() {
        dataStore.edit { prefs ->
            prefs[KEY_FAILED_ATTEMPTS] = 0
            prefs[KEY_LOCKOUT_UNTIL_EPOCH_MS] = 0L
        }
    }

    override suspend fun getLockoutState(nowEpochMs: Long): LockoutState {
        val prefs = dataStore.data.catch { emit(emptyPreferences()) }.first()
        val failedAttempts = prefs[KEY_FAILED_ATTEMPTS] ?: 0
        val lockoutUntil = prefs[KEY_LOCKOUT_UNTIL_EPOCH_MS] ?: 0L

        return if (lockoutUntil > nowEpochMs) {
            val remainingMs = lockoutUntil - nowEpochMs
            val remainingSec = ceil(remainingMs / 1000.0).toLong().coerceAtLeast(1L)
            LockoutState(
                isLockedOut = true,
                remainingSeconds = remainingSec,
                failedAttempts = failedAttempts
            )
        } else {
            LockoutState(
                isLockedOut = false,
                remainingSeconds = 0L,
                failedAttempts = failedAttempts
            )
        }
    }

    companion object {
        private val KEY_PIN_HASH = stringPreferencesKey("pin_hash_hex")
        private val KEY_PIN_SALT = stringPreferencesKey("pin_salt_hex")
        private val KEY_PIN_ITERATIONS = intPreferencesKey("pin_iterations")
        private val KEY_FAILED_ATTEMPTS = intPreferencesKey("failed_attempts")
        private val KEY_LOCKOUT_UNTIL_EPOCH_MS = longPreferencesKey("lockout_until_epoch_ms")
    }
}
