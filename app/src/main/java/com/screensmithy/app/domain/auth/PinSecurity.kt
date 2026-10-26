package com.screensmithy.app.domain.auth

import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import java.security.spec.InvalidKeySpecException
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Data structure representing a securely derived and salted PIN.
 * Plaintext PIN is never stored or retained.
 */
data class HashedPin(
    val hashHex: String,
    val saltHex: String,
    val iterations: Int = PinSecurity.DEFAULT_ITERATIONS
) {
    init {
        require(hashHex.isNotBlank()) { "hashHex must not be blank" }
        require(saltHex.isNotBlank()) { "saltHex must not be blank" }
        require(iterations >= PinSecurity.MIN_ITERATIONS) {
            "iterations ($iterations) must be at least ${PinSecurity.MIN_ITERATIONS}"
        }
    }
}

/**
 * Cryptographic utility for hashing and constant-time verification of admin PINs.
 * Uses PBKDF2WithHmacSHA256 with 128-bit cryptographic salt and 100,000 iterations.
 */
object PinSecurity {

    const val PIN_LENGTH: Int = 4
    const val MIN_PIN_LENGTH: Int = 4
    const val MAX_PIN_LENGTH: Int = 4
    const val DEFAULT_ITERATIONS: Int = 100_000
    const val MIN_ITERATIONS: Int = 50_000
    private const val SALT_BYTES: Int = 16
    private const val KEY_LENGTH_BITS: Int = 256
    private const val ALGORITHM: String = "PBKDF2WithHmacSHA256"

    private val secureRandom = SecureRandom()

    /**
     * Validates PIN format. PIN must contain exactly [PIN_LENGTH] (4) numeric digits.
     */
    fun validatePinFormat(pin: CharArray): PinValidationResult {
        if (pin.size < PIN_LENGTH) {
            return PinValidationResult.TooShort(PIN_LENGTH)
        }
        if (pin.size > PIN_LENGTH) {
            return PinValidationResult.TooLong(PIN_LENGTH)
        }
        if (pin.any { !it.isDigit() }) {
            return PinValidationResult.NonNumeric
        }
        return PinValidationResult.Valid
    }

    /**
     * Hashes candidate PIN using PBKDF2WithHmacSHA256.
     * Generates a fresh random cryptographic salt if none is provided.
     */
    fun hashPin(
        pin: CharArray,
        salt: ByteArray = generateSalt(),
        iterations: Int = DEFAULT_ITERATIONS
    ): HashedPin {
        val validation = validatePinFormat(pin)
        require(validation is PinValidationResult.Valid) {
            "PIN does not satisfy security format requirements"
        }

        val keySpec = PBEKeySpec(pin, salt, iterations, KEY_LENGTH_BITS)
        try {
            val keyFactory = SecretKeyFactory.getInstance(ALGORITHM)
            val derivedBytes = keyFactory.generateSecret(keySpec).encoded
            return HashedPin(
                hashHex = derivedBytes.toHex(),
                saltHex = salt.toHex(),
                iterations = iterations
            )
        } catch (e: NoSuchAlgorithmException) {
            throw IllegalStateException("Cryptographic algorithm $ALGORITHM not available on platform", e)
        } catch (e: InvalidKeySpecException) {
            throw IllegalStateException("Invalid key specification for PBKDF2 derivation", e)
        } finally {
            keySpec.clearPassword()
        }
    }

    /**
     * Verifies [candidatePin] against [storedHash] using constant-time byte comparison
     * to eliminate timing side-channel attacks.
     */
    fun verifyPin(candidatePin: CharArray, storedHash: HashedPin): Boolean {
        val salt = try {
            storedHash.saltHex.hexToBytes()
        } catch (e: IllegalArgumentException) {
            return false
        }

        val keySpec = PBEKeySpec(candidatePin, salt, storedHash.iterations, KEY_LENGTH_BITS)
        try {
            val keyFactory = SecretKeyFactory.getInstance(ALGORITHM)
            val candidateBytes = keyFactory.generateSecret(keySpec).encoded
            val storedBytes = storedHash.hashHex.hexToBytes()

            // Safe, constant-time comparison
            return MessageDigest.isEqual(candidateBytes, storedBytes)
        } catch (e: Exception) {
            return false
        } finally {
            keySpec.clearPassword()
        }
    }

    private fun generateSalt(): ByteArray {
        val salt = ByteArray(SALT_BYTES)
        secureRandom.nextBytes(salt)
        return salt
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray {
        require(length % 2 == 0) { "Hex string length must be even" }
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}

sealed interface PinValidationResult {
    data object Valid : PinValidationResult
    data class TooShort(val minLength: Int) : PinValidationResult
    data class TooLong(val maxLength: Int) : PinValidationResult
    data object NonNumeric : PinValidationResult
}
