package com.screensmithy.app.domain

import com.screensmithy.app.domain.model.ContentSource
import com.screensmithy.app.domain.model.DisplayPreferences
import com.screensmithy.app.domain.model.KioskConfiguration
import com.screensmithy.app.domain.model.LocalHtmlMetadata
import com.screensmithy.app.domain.model.NavigationMode
import com.screensmithy.app.domain.model.NavigationPolicy
import com.screensmithy.app.domain.model.RetryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainModelTest {

    @Test
    fun defaultConfiguration_hasExpectedDefaults() {
        val config = KioskConfiguration.DEFAULT

        assertEquals(1, config.schemaVersion)
        assertFalse(config.isSetupCompleted)
        assertEquals(ContentSource.None, config.contentSource)
        assertEquals(NavigationMode.CONFIGURED_HOST_ONLY, config.navigationPolicy.mode)
        assertTrue(config.navigationPolicy.trustedHosts.isEmpty())
        assertTrue(config.displayPreferences.keepScreenAwake)
        assertEquals(3, config.retryPolicy.maxRetries)
        assertEquals(5, config.retryPolicy.initialBackoffSeconds)
        assertEquals(60, config.retryPolicy.maxBackoffSeconds)
    }

    @Test
    fun localHtmlMetadata_validatesInvariants() {
        val valid = LocalHtmlMetadata(
            documentId = "doc_123",
            displayName = "Dashboard.html",
            relativePath = "html/doc_123/index.html",
            sizeBytes = 1024,
            importedAtEpochMs = 1700000000000L,
            sha256Checksum = "abc123hash"
        )
        assertEquals("doc_123", valid.documentId)
        assertEquals("Dashboard.html", valid.displayName)
    }

    @Test(expected = IllegalArgumentException::class)
    fun localHtmlMetadata_rejectsAbsolutePaths() {
        LocalHtmlMetadata(
            documentId = "doc_1",
            displayName = "test.html",
            relativePath = "/etc/passwd",
            sizeBytes = 100,
            importedAtEpochMs = 1000L
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun localHtmlMetadata_rejectsPathTraversal() {
        LocalHtmlMetadata(
            documentId = "doc_1",
            displayName = "test.html",
            relativePath = "../internal/file.html",
            sizeBytes = 100,
            importedAtEpochMs = 1000L
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun localHtmlMetadata_rejectsExcessiveSize() {
        LocalHtmlMetadata(
            documentId = "doc_1",
            displayName = "test.html",
            relativePath = "files/test.html",
            sizeBytes = LocalHtmlMetadata.MAX_IMPORT_SIZE_BYTES + 1,
            importedAtEpochMs = 1000L
        )
    }

    @Test
    fun retryPolicy_validatesBounds() {
        val validPolicy = RetryPolicy(maxRetries = 5, initialBackoffSeconds = 10, maxBackoffSeconds = 120)
        assertEquals(5, validPolicy.maxRetries)
        assertEquals(10, validPolicy.initialBackoffSeconds)
        assertEquals(120, validPolicy.maxBackoffSeconds)
    }

    @Test(expected = IllegalArgumentException::class)
    fun retryPolicy_rejectsTooManyRetries() {
        RetryPolicy(maxRetries = 15)
    }

    @Test(expected = IllegalArgumentException::class)
    fun retryPolicy_rejectsInitialBackoffGreaterThanMax() {
        RetryPolicy(initialBackoffSeconds = 100, maxBackoffSeconds = 50)
    }
}
