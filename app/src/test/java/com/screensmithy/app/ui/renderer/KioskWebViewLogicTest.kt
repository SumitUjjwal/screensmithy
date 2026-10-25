package com.screensmithy.app.ui.renderer

import com.screensmithy.app.domain.model.ContentSource
import com.screensmithy.app.domain.model.LocalHtmlMetadata
import com.screensmithy.app.domain.model.NavigationPolicy
import com.screensmithy.app.domain.renderer.RendererState
import com.screensmithy.app.domain.url.UrlValidationError
import com.screensmithy.app.domain.url.UrlValidationResult
import com.screensmithy.app.domain.url.UrlValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class KioskWebViewLogicTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `missing local content generates MissingLocalContent state`() {
        val rootDir = tempFolder.root
        val missingRelativePath = "imported_html/doc_missing/index.html"
        val file = File(rootDir, missingRelativePath)

        val metadata = LocalHtmlMetadata(
            documentId = "doc_missing",
            displayName = "index.html",
            relativePath = missingRelativePath,
            sizeBytes = 1024,
            importedAtEpochMs = 1700000000L
        )

        val state = if (!file.exists()) {
            RendererState.MissingLocalContent(
                relativePath = metadata.relativePath,
                reason = "Imported HTML file was not found in app-private storage: ${file.absolutePath}"
            )
        } else {
            RendererState.Ready("url")
        }

        assertTrue(state is RendererState.MissingLocalContent)
        val missingState = state as RendererState.MissingLocalContent
        assertEquals(missingRelativePath, missingState.relativePath)
        assertTrue(missingState.reason.contains("not found"))
    }

    @Test
    fun `valid local content constructs virtual WebViewAssetLoader URL correctly`() {
        val rootDir = tempFolder.root
        val htmlDir = File(rootDir, "imported_html/doc_123").apply { mkdirs() }
        val indexFile = File(htmlDir, "index.html").apply { writeText("<h1>Kiosk Test</h1>") }

        val relativePath = "imported_html/doc_123/index.html"
        val subpath = relativePath
            .removePrefix(KioskWebView.LOCAL_STORAGE_DIR_NAME)
            .removePrefix("/")

        val virtualUrl = "https://${KioskWebView.ASSET_LOADER_DOMAIN}${KioskWebView.ASSET_LOADER_PATH}$subpath"

        assertEquals(
            "https://appassets.androidplatform.net/imported_content/doc_123/index.html",
            virtualUrl
        )
        assertTrue(File(rootDir, relativePath).exists())
    }

    @Test
    fun `invalid remote URL is rejected before loading`() {
        val invalidUrl = "javascript:alert(1)"
        val validation = UrlValidator.validate(invalidUrl)

        assertTrue(validation is UrlValidationResult.Invalid)
        val invalid = validation as UrlValidationResult.Invalid
        assertTrue(invalid.error is UrlValidationError.UnsupportedScheme)

        val state = RendererState.MainFrameError(
            errorCode = -1,
            description = invalid.userMessage,
            failingUrl = invalidUrl
        )

        assertEquals(-1, state.errorCode)
        assertEquals(invalidUrl, state.failingUrl)
    }

    @Test
    fun `valid remote URL resolves correctly for KioskWebView`() {
        val remoteUrl = "https://dashboard.lan:8443/view"
        val validation = UrlValidator.validate(remoteUrl)

        assertTrue(validation is UrlValidationResult.Valid)
        val valid = validation as UrlValidationResult.Valid
        assertEquals("https://dashboard.lan:8443/view", valid.normalizedUrl)
    }
}
