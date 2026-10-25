package com.screensmithy.app.domain.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretSanitizerTest {

    @Test
    fun sanitizeUrl_urlWithoutQueryParams_isUnchanged() {
        val url = "https://kiosk.example.com/display"
        val result = SecretSanitizer.sanitizeUrl(url)
        assertEquals(url, result)
    }

    @Test
    fun sanitizeUrl_urlWithSafeParams_preservesSafeParams() {
        val url = "https://kiosk.example.com/display?view=fullscreen&page=2&lang=en"
        val result = SecretSanitizer.sanitizeUrl(url)
        assertEquals(url, result)
    }

    @Test
    fun sanitizeUrl_urlWithToken_redactsToken() {
        val url = "https://kiosk.example.com/dash?user=alice&token=supersecret123&theme=dark"
        val result = SecretSanitizer.sanitizeUrl(url)
        assertEquals(
            "https://kiosk.example.com/dash?user=alice&token=REDACTED&theme=dark",
            result
        )
    }

    @Test
    fun sanitizeUrl_urlWithApiKeyAndSecret_redactsBoth() {
        val url = "https://api.example.com/v1?apikey=12345&secret=mysecret&format=json"
        val result = SecretSanitizer.sanitizeUrl(url)
        assertEquals(
            "https://api.example.com/v1?apikey=REDACTED&secret=REDACTED&format=json",
            result
        )
    }

    @Test
    fun sanitizeUrl_caseInsensitiveParamNames_areRedacted() {
        val url = "https://kiosk.example.com/?API_KEY=ABCDE&Password=pass"
        val result = SecretSanitizer.sanitizeUrl(url)
        assertEquals(
            "https://kiosk.example.com/?API_KEY=REDACTED&Password=REDACTED",
            result
        )
    }

    @Test
    fun sanitizeUrl_withEmbeddedUserInfo_stripsCredentials() {
        val url = "https://admin:supersecret@kiosk.example.com/home"
        val result = SecretSanitizer.sanitizeUrl(url)
        assertEquals("https://***:***@kiosk.example.com/home", result)
    }

    @Test
    fun sanitizeUrl_withOAuthFragment_redactsFragmentToken() {
        val url = "https://kiosk.example.com/cb#access_token=ya29.xyz&state=123"
        val result = SecretSanitizer.sanitizeUrl(url)
        assertEquals("https://kiosk.example.com/cb#access_token=REDACTED&state=123", result)
    }

    @Test
    fun sanitizeMessage_withAuthBearerHeader_redactsBearerToken() {
        val msg = "HTTP Request failed: Authorization: Bearer abc123def456xyz"
        val result = SecretSanitizer.sanitizeMessage(msg)
        assertEquals("HTTP Request failed: Authorization: REDACTED", result)
    }

    @Test
    fun sanitizeMessage_withCookieHeader_redactsCookie() {
        val msg = "Cookie: session_id=sess_999; theme=dark"
        val result = SecretSanitizer.sanitizeMessage(msg)
        assertEquals("Cookie: REDACTED", result)
    }

    @Test
    fun sanitizeMessage_containingEmbeddedUrl_sanitizesEmbeddedUrl() {
        val msg = "Navigated to https://example.com/kiosk?token=topsecret123 successfully"
        val result = SecretSanitizer.sanitizeMessage(msg)
        assertTrue(result.contains("token=REDACTED"))
        assertFalse(result.contains("topsecret123"))
    }
}
