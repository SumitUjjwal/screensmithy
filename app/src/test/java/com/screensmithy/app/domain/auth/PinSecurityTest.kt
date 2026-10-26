package com.screensmithy.app.domain.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinSecurityTest {

    @Test
    fun `validatePinFormat accepts valid 4 digit numeric PINs`() {
        assertEquals(PinValidationResult.Valid, PinSecurity.validatePinFormat("1234".toCharArray()))
        assertEquals(PinValidationResult.Valid, PinSecurity.validatePinFormat("0000".toCharArray()))
        assertEquals(PinValidationResult.Valid, PinSecurity.validatePinFormat("9876".toCharArray()))
        assertEquals(PinValidationResult.Valid, PinSecurity.validatePinFormat("5555".toCharArray()))
    }

    @Test
    fun `validatePinFormat rejects PINs shorter than 4 digits`() {
        val result = PinSecurity.validatePinFormat("123".toCharArray())
        assertTrue(result is PinValidationResult.TooShort)
        assertEquals(4, (result as PinValidationResult.TooShort).minLength)
    }

    @Test
    fun `validatePinFormat rejects PINs longer than 4 digits`() {
        val result = PinSecurity.validatePinFormat("12345".toCharArray())
        assertTrue(result is PinValidationResult.TooLong)
        assertEquals(4, (result as PinValidationResult.TooLong).maxLength)

        val result2 = PinSecurity.validatePinFormat("123456".toCharArray())
        assertTrue(result2 is PinValidationResult.TooLong)
        assertEquals(4, (result2 as PinValidationResult.TooLong).maxLength)
    }

    @Test
    fun `validatePinFormat rejects non-numeric characters`() {
        assertEquals(PinValidationResult.NonNumeric, PinSecurity.validatePinFormat("123a".toCharArray()))
        assertEquals(PinValidationResult.NonNumeric, PinSecurity.validatePinFormat("12-4".toCharArray()))
        assertEquals(PinValidationResult.NonNumeric, PinSecurity.validatePinFormat("    ".toCharArray()))
    }

    @Test
    fun `hashPin creates valid PBKDF2 hash with random salt`() {
        val pin = "8492".toCharArray()
        val hashed = PinSecurity.hashPin(pin)

        assertEquals(64, hashed.hashHex.length) // 256 bits = 32 bytes = 64 hex chars
        assertEquals(32, hashed.saltHex.length) // 128 bits = 16 bytes = 32 hex chars
        assertEquals(PinSecurity.DEFAULT_ITERATIONS, hashed.iterations)

        // Hashing the same PIN again must produce a different salt and hash
        val hashed2 = PinSecurity.hashPin(pin)
        assertNotEquals(hashed.saltHex, hashed2.saltHex)
        assertNotEquals(hashed.hashHex, hashed2.hashHex)
    }

    @Test
    fun `verifyPin returns true for correct candidate PIN`() {
        val pin = "5829".toCharArray()
        val hashed = PinSecurity.hashPin(pin)

        val candidate = "5829".toCharArray()
        assertTrue("Matching PIN candidate must verify successfully", PinSecurity.verifyPin(candidate, hashed))
    }

    @Test
    fun `verifyPin returns false for incorrect candidate PIN`() {
        val pin = "5829".toCharArray()
        val hashed = PinSecurity.hashPin(pin)

        val wrongCandidate = "5828".toCharArray()
        assertFalse("Mismatched PIN candidate must fail verification", PinSecurity.verifyPin(wrongCandidate, hashed))

        val shortCandidate = "582".toCharArray()
        assertFalse("Different length PIN candidate must fail verification", PinSecurity.verifyPin(shortCandidate, hashed))
    }

    @Test
    fun `verifyPin safely handles corrupted stored hash or salt`() {
        val corruptedSalt = HashedPin(
            hashHex = "a".repeat(64),
            saltHex = "invalid_hex!",
            iterations = 100_000
        )
        assertFalse(PinSecurity.verifyPin("1234".toCharArray(), corruptedSalt))
    }
}
