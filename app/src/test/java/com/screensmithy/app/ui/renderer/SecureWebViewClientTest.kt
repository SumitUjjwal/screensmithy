package com.screensmithy.app.ui.renderer

import android.net.Uri
import android.net.http.SslError
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import com.screensmithy.app.domain.model.NavigationMode
import com.screensmithy.app.domain.model.NavigationPolicy
import com.screensmithy.app.domain.renderer.RendererEvent
import com.screensmithy.app.domain.renderer.RendererState
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SecureWebViewClientTest {

    private val assetLoader = mockk<WebViewAssetLoader>(relaxed = true)
    private val stateUpdates = mutableListOf<RendererState>()
    private val emittedEvents = mutableListOf<RendererEvent>()

    private lateinit var client: SecureWebViewClient

    @Before
    fun setUp() {
        stateUpdates.clear()
        emittedEvents.clear()
        client = SecureWebViewClient(
            assetLoader = assetLoader,
            configuredUrl = "https://kiosk.example.com/display",
            navigationPolicy = NavigationPolicy(
                mode = NavigationMode.CONFIGURED_HOST_ONLY,
                includeSubdomains = false
            ),
            onStateChange = { stateUpdates.add(it) },
            onEvent = { emittedEvents.add(it) }
        )
    }

    private fun mockRequest(url: String, isForMainFrame: Boolean = true): WebResourceRequest {
        val mockUri = mockk<Uri>()
        every { mockUri.toString() } returns url
        val request = mockk<WebResourceRequest>()
        every { request.url } returns mockUri
        every { request.isForMainFrame } returns isForMainFrame
        return request
    }

    @Test
    fun `navigation within configured domain returns false and emits no blocked events`() {
        val request = mockRequest("https://kiosk.example.com/display/page2")

        val blocked = client.shouldOverrideUrlLoading(null, request)

        assertFalse("Navigation within configured domain must be permitted", blocked)
        assertTrue("No blocked events should be emitted", emittedEvents.isEmpty())
    }

    @Test
    fun `navigation outside configured domain returns true and emits NavigationBlocked event`() {
        val request = mockRequest("https://malicious.example.org/phish")

        val blocked = client.shouldOverrideUrlLoading(null, request)

        assertTrue("Navigation outside configured domain must be blocked", blocked)
        assertEquals(1, emittedEvents.size)
        val event = emittedEvents.first() as RendererEvent.NavigationBlocked
        assertEquals("https://malicious.example.org/phish", event.targetUrl)
    }

    @Test
    fun `external app scheme intent triggers ExternalAppLaunchBlocked and NavigationBlocked`() {
        val intentUrl = "intent://scan/#Intent;scheme=zxing;package=com.google.zxing.client.android;end"
        val request = mockRequest(intentUrl)

        val blocked = client.shouldOverrideUrlLoading(null, request)

        assertTrue("External app intent scheme must be blocked", blocked)
        assertTrue(emittedEvents.any { it is RendererEvent.ExternalAppLaunchBlocked && it.targetUrl == intentUrl })
        assertTrue(emittedEvents.any { it is RendererEvent.NavigationBlocked && it.targetUrl == intentUrl })
    }

    @Test
    fun `custom scheme tel and mailto trigger ExternalAppLaunchBlocked`() {
        val telUrl = "tel:1234567890"
        val request = mockRequest(telUrl)

        val blocked = client.shouldOverrideUrlLoading(null, request)

        assertTrue("Telephone scheme must be blocked", blocked)
        assertTrue(emittedEvents.any { it is RendererEvent.ExternalAppLaunchBlocked && it.targetUrl == telUrl })
    }

    @Test
    fun `deprecated string shouldOverrideUrlLoading behaves identically`() {
        @Suppress("DEPRECATION")
        val blocked = client.shouldOverrideUrlLoading(null, "https://kiosk.example.com/display/subpage")
        assertFalse(blocked)

        @Suppress("DEPRECATION")
        val blockedExternal = client.shouldOverrideUrlLoading(null, "https://unauthorized.org")
        assertTrue(blockedExternal)
    }

    @Test
    fun `onPageStarted resets error flag and emits Loading state`() {
        client.onPageStarted(null, "https://kiosk.example.com/display", null)

        assertFalse(client.hasMainFrameError)
        assertEquals(1, stateUpdates.size)
        val state = stateUpdates.first() as RendererState.Loading
        assertEquals("https://kiosk.example.com/display", state.url)
        assertEquals(0, state.progressPercent)
    }

    @Test
    fun `onPageFinished emits Ready state when no error occurred`() {
        client.onPageStarted(null, "https://kiosk.example.com/display", null)
        client.onPageFinished(null, "https://kiosk.example.com/display")

        assertEquals(2, stateUpdates.size)
        assertTrue(stateUpdates[1] is RendererState.Ready)
        val ready = stateUpdates[1] as RendererState.Ready
        assertEquals("https://kiosk.example.com/display", ready.url)
    }

    @Test
    fun `onReceivedError for main frame emits MainFrameError and sets error flag`() {
        val request = mockRequest("https://kiosk.example.com/display", isForMainFrame = true)

        val error = mockk<WebResourceError>()
        every { error.errorCode } returns -2 // ERROR_HOST_LOOKUP
        every { error.description } returns "net::ERR_NAME_NOT_RESOLVED"

        client.onReceivedError(null, request, error)

        assertTrue(client.hasMainFrameError)
        assertEquals(1, stateUpdates.size)
        val state = stateUpdates.first() as RendererState.MainFrameError
        assertEquals(-2, state.errorCode)
        assertEquals("net::ERR_NAME_NOT_RESOLVED", state.description)
        assertEquals("https://kiosk.example.com/display", state.failingUrl)

        // Subsequent onPageFinished must NOT overwrite MainFrameError with Ready
        client.onPageFinished(null, "https://kiosk.example.com/display")
        assertEquals("onPageFinished should not emit Ready after main-frame error", 1, stateUpdates.size)
    }

    @Test
    fun `onReceivedError for subresource is ignored and does not block Ready state`() {
        val request = mockRequest("https://kiosk.example.com/analytics.js", isForMainFrame = false)

        val error = mockk<WebResourceError>()
        every { error.errorCode } returns -1
        every { error.description } returns "Failed subresource"

        client.onReceivedError(null, request, error)

        assertFalse("Subresource errors must not set hasMainFrameError", client.hasMainFrameError)
        assertTrue("Subresource errors must not emit MainFrameError state", stateUpdates.isEmpty())

        // onPageFinished proceeds to Ready
        client.onPageFinished(null, "https://kiosk.example.com/display")
        assertEquals(1, stateUpdates.size)
        assertTrue(stateUpdates.first() is RendererState.Ready)
    }

    @Test
    fun `onReceivedHttpError for main frame emits HttpError`() {
        val request = mockRequest("https://kiosk.example.com/display", isForMainFrame = true)

        val response = mockk<WebResourceResponse>()
        every { response.statusCode } returns 502
        every { response.reasonPhrase } returns "Bad Gateway"

        client.onReceivedHttpError(null, request, response)

        assertTrue(client.hasMainFrameError)
        assertEquals(1, stateUpdates.size)
        val state = stateUpdates.first() as RendererState.HttpError
        assertEquals(502, state.statusCode)
        assertEquals("Bad Gateway", state.reasonPhrase)
    }

    @Test
    fun `onReceivedHttpError for subresource is ignored`() {
        val request = mockRequest("https://kiosk.example.com/pixel.png", isForMainFrame = false)

        val response = mockk<WebResourceResponse>()
        every { response.statusCode } returns 404
        every { response.reasonPhrase } returns "Not Found"

        client.onReceivedHttpError(null, request, response)

        assertFalse(client.hasMainFrameError)
        assertTrue(stateUpdates.isEmpty())
    }

    @Test
    fun `onReceivedSslError strictly cancels request and emits TlsError`() {
        val handler = mockk<SslErrorHandler>(relaxed = true)
        val sslError = mockk<SslError>()
        every { sslError.url } returns "https://self-signed.internal.lan"
        every { sslError.primaryError } returns SslError.SSL_UNTRUSTED

        client.onReceivedSslError(null, handler, sslError)

        // FAIL-CLOSED VERIFICATION: strictly cancelled, never proceeded
        verify(exactly = 1) { handler.cancel() }
        verify(exactly = 0) { handler.proceed() }

        assertTrue(client.hasMainFrameError)
        assertEquals(1, stateUpdates.size)
        val state = stateUpdates.first() as RendererState.TlsError
        assertEquals("https://self-signed.internal.lan", state.failingUrl)
        assertEquals(SslError.SSL_UNTRUSTED, state.primaryErrorCode)
        assertEquals("Certificate authority is untrusted", state.errorDescription)
    }

    @Test
    fun `onRenderProcessGone emits RendererCrash and returns true`() {
        val detail = mockk<RenderProcessGoneDetail>()
        every { detail.didCrash() } returns true

        val handled = client.onRenderProcessGone(null, detail)

        assertTrue("onRenderProcessGone must return true to prevent app crash", handled)
        assertTrue(client.hasMainFrameError)
        assertEquals(1, stateUpdates.size)
        val state = stateUpdates.first() as RendererState.RendererCrash
        assertTrue(state.didCrash)
    }
}
