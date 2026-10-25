package com.screensmithy.app.domain.url

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlValidatorTest {

    @Test
    fun validate_whenBlankOrNull_returnsBlankInputError() {
        val nullResult = UrlValidator.validate(null)
        assertTrue(nullResult is UrlValidationResult.Invalid)
        assertEquals(UrlValidationError.BlankInput, (nullResult as UrlValidationResult.Invalid).error)

        val blankResult = UrlValidator.validate("   ")
        assertTrue(blankResult is UrlValidationResult.Invalid)
        assertEquals(UrlValidationError.BlankInput, (blankResult as UrlValidationResult.Invalid).error)
    }

    @Test
    fun validate_whenContainsWhitespace_returnsMalformedUri() {
        val result = UrlValidator.validate("https://example.com/path with spaces")
        assertTrue(result is UrlValidationResult.Invalid)
        assertTrue((result as UrlValidationResult.Invalid).error is UrlValidationError.MalformedUri)
    }

    @Test
    fun validate_normalizesBareDomainToHttps() {
        val result = UrlValidator.validate("kiosk.example.com")
        assertTrue(result is UrlValidationResult.Valid)
        val valid = result as UrlValidationResult.Valid
        assertEquals("https://kiosk.example.com/", valid.normalizedUrl)
        assertEquals("https", valid.scheme)
        assertEquals("kiosk.example.com", valid.host)
        assertNull(valid.port)
        assertFalse(valid.hasInsecureHttpWarning)
    }

    @Test
    fun validate_normalizesBareDomainWithPortAndPath() {
        val result = UrlValidator.validate("dashboard.lan:8443/tv?mode=full")
        assertTrue(result is UrlValidationResult.Valid)
        val valid = result as UrlValidationResult.Valid
        assertEquals("https://dashboard.lan:8443/tv?mode=full", valid.normalizedUrl)
        assertEquals(8443, valid.port)
        assertEquals("dashboard.lan", valid.host)
    }

    @Test
    fun validate_normalizesBareIpv4Address() {
        val result = UrlValidator.validate("192.168.1.100:8080/display")
        assertTrue(result is UrlValidationResult.Valid)
        val valid = result as UrlValidationResult.Valid
        assertEquals("https://192.168.1.100:8080/display", valid.normalizedUrl)
        assertEquals("192.168.1.100", valid.host)
        assertEquals(8080, valid.port)
    }

    @Test
    fun validate_normalizesIntranetHostname() {
        val result = UrlValidator.validate("kiosk-screen:3000/app")
        assertTrue(result is UrlValidationResult.Valid)
        val valid = result as UrlValidationResult.Valid
        assertEquals("https://kiosk-screen:3000/app", valid.normalizedUrl)
        assertEquals("kiosk-screen", valid.host)
    }

    @Test
    fun validate_mixedCaseUrl_lowercasesSchemeAndHostWhilePreservingPath() {
        val result = UrlValidator.validate("HTTPS://Kiosk.Example.COM:443/SomePath/Index?Key=Value#Section")
        assertTrue(result is UrlValidationResult.Valid)
        val valid = result as UrlValidationResult.Valid
        assertEquals("https://kiosk.example.com/SomePath/Index?Key=Value#Section", valid.normalizedUrl)
        assertEquals("kiosk.example.com", valid.host)
        assertNull(valid.port) // Standard port 443 normalized
    }

    @Test
    fun validate_httpUrl_generatesInsecureTransportWarning() {
        val result = UrlValidator.validate("http://insecure.internal.lan/status")
        assertTrue(result is UrlValidationResult.Valid)
        val valid = result as UrlValidationResult.Valid
        assertEquals("http", valid.scheme)
        assertTrue(valid.hasInsecureHttpWarning)
        assertTrue(valid.warningMessage?.contains("HTTP") == true)
    }

    @Test
    fun validate_unsupportedSchemes_failClosed() {
        val dangerousSchemes = listOf(
            "javascript:alert(document.cookie)",
            "file:///sdcard/Android/data/secret.html",
            "content://com.screensmithy.provider/files",
            "intent://host/#Intent;scheme=http;end",
            "data:text/html,<h1>Hello</h1>",
            "mailto:admin@example.com",
            "ftp://ftp.example.com/kiosk.html"
        )

        for (url in dangerousSchemes) {
            val result = UrlValidator.validate(url)
            assertTrue("Expected rejection for: $url", result is UrlValidationResult.Invalid)
            val error = (result as UrlValidationResult.Invalid).error
            assertTrue("Expected UnsupportedScheme for: $url", error is UrlValidationError.UnsupportedScheme)
        }
    }

    @Test
    fun validate_embeddedCredentials_areRejectedForSecurity() {
        val result = UrlValidator.validate("https://admin:superSecretPassword@kiosk.example.com/secure")
        assertTrue(result is UrlValidationResult.Invalid)
        val error = (result as UrlValidationResult.Invalid).error
        assertTrue(error is UrlValidationError.EmbeddedCredentialsNotAllowed)
    }

    @Test
    fun validate_missingHost_isRejected() {
        val result = UrlValidator.validate("https:///path/without/host")
        assertTrue(result is UrlValidationResult.Invalid)
        val error = (result as UrlValidationResult.Invalid).error
        assertTrue(error is UrlValidationError.MissingHost)
    }

    @Test
    fun validate_invalidPort_isRejected() {
        val result = UrlValidator.validate("https://example.com:70000/app")
        assertTrue(result is UrlValidationResult.Invalid)
        val error = (result as UrlValidationResult.Invalid).error
        assertTrue(error is UrlValidationError.InvalidPort)
        assertEquals(70000, (error as UrlValidationError.InvalidPort).port)
    }
}
