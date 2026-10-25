package com.screensmithy.app.domain.recovery

import com.screensmithy.app.domain.renderer.RendererState
import com.screensmithy.app.domain.state.ConfigurationError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorClassifierTest {

    @Test
    fun `MainFrameError is classified as retryable network error`() {
        val state = RendererState.MainFrameError(
            errorCode = -2,
            description = "net::ERR_NAME_NOT_RESOLVED",
            failingUrl = "https://example.com"
        )

        val result = ErrorClassifier.classifyRendererState(state)

        assertTrue(result is ErrorClassification.Retryable)
        val retryable = result as ErrorClassification.Retryable
        assertTrue(retryable.isNetworkRelated)
        assertTrue(retryable.technicalDetails.contains("net::ERR_NAME_NOT_RESOLVED"))
    }

    @Test
    fun `HttpError with 5xx or transient status is classified as retryable`() {
        val testCodes = listOf(500, 502, 503, 504, 408, 429)

        for (code in testCodes) {
            val state = RendererState.HttpError(
                statusCode = code,
                reasonPhrase = "Server Error",
                failingUrl = "https://example.com"
            )
            val result = ErrorClassifier.classifyRendererState(state)

            assertTrue("HTTP $code should be retryable", result is ErrorClassification.Retryable)
            val retryable = result as ErrorClassification.Retryable
            assertTrue(retryable.isNetworkRelated)
        }
    }

    @Test
    fun `HttpError with permanent 4xx status is classified as non-retryable client error`() {
        val testCodes = listOf(400, 401, 403, 404, 405, 410)

        for (code in testCodes) {
            val state = RendererState.HttpError(
                statusCode = code,
                reasonPhrase = "Client Error",
                failingUrl = "https://example.com"
            )
            val result = ErrorClassifier.classifyRendererState(state)

            assertTrue("HTTP $code should be non-retryable", result is ErrorClassification.NonRetryable)
            val nonRetryable = result as ErrorClassification.NonRetryable
            assertEquals(NonRetryableReason.PERMANENT_CLIENT_ERROR, nonRetryable.reason)
        }
    }

    @Test
    fun `TlsError is strictly classified as non-retryable TLS failure`() {
        val state = RendererState.TlsError(
            primaryErrorCode = 3,
            errorDescription = "SSL_UNTRUSTED",
            failingUrl = "https://self-signed.badssl.com"
        )

        val result = ErrorClassifier.classifyRendererState(state)

        assertTrue(result is ErrorClassification.NonRetryable)
        val nonRetryable = result as ErrorClassification.NonRetryable
        assertEquals(NonRetryableReason.TLS_FAILURE, nonRetryable.reason)
        assertTrue(nonRetryable.userMessage.contains("TLS/SSL"))
    }

    @Test
    fun `MissingLocalContent is classified as non-retryable missing file`() {
        val state = RendererState.MissingLocalContent(
            relativePath = "imported_html/index.html",
            reason = "File does not exist"
        )

        val result = ErrorClassifier.classifyRendererState(state)

        assertTrue(result is ErrorClassification.NonRetryable)
        val nonRetryable = result as ErrorClassification.NonRetryable
        assertEquals(NonRetryableReason.MISSING_LOCAL_FILE, nonRetryable.reason)
    }

    @Test
    fun `RendererCrash is classified as retryable renderer termination`() {
        val state = RendererState.RendererCrash(didCrash = true)

        val result = ErrorClassifier.classifyRendererState(state)

        assertTrue(result is ErrorClassification.Retryable)
        val retryable = result as ErrorClassification.Retryable
        assertFalse(retryable.isNetworkRelated)
    }

    @Test
    fun `all ConfigurationErrors are classified as non-retryable`() {
        val invalidUrl = ConfigurationError.InvalidUrl("http://bad", "Scheme not allowed")
        val missingFile = ConfigurationError.MissingLocalFile("test.html", "Not found")
        val unsupportedSchema = ConfigurationError.UnsupportedSchemaVersion(99, 1)
        val corrupted = ConfigurationError.CorruptedData("Invalid JSON")
        val invalidParam = ConfigurationError.InvalidConfigurationParameter("retries", "Negative")

        assertEquals(NonRetryableReason.INVALID_CONFIGURATION, ErrorClassifier.classifyConfigurationError(invalidUrl).reason)
        assertEquals(NonRetryableReason.MISSING_LOCAL_FILE, ErrorClassifier.classifyConfigurationError(missingFile).reason)
        assertEquals(NonRetryableReason.INVALID_CONFIGURATION, ErrorClassifier.classifyConfigurationError(unsupportedSchema).reason)
        assertEquals(NonRetryableReason.INVALID_CONFIGURATION, ErrorClassifier.classifyConfigurationError(corrupted).reason)
        assertEquals(NonRetryableReason.INVALID_CONFIGURATION, ErrorClassifier.classifyConfigurationError(invalidParam).reason)
    }
}
