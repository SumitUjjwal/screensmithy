package com.screensmithy.app.ui.renderer

import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureWebChromeClientTest {

    @Test
    fun `onPermissionRequest denies camera, mic, and hardware permissions`() {
        val request = mockk<PermissionRequest>(relaxed = true)
        val client = SecureWebChromeClient()

        client.onPermissionRequest(request)

        verify(exactly = 1) { request.deny() }
    }

    @Test
    fun `onGeolocationPermissionsShowPrompt denies location request`() {
        val callback = mockk<GeolocationPermissions.Callback>(relaxed = true)
        val client = SecureWebChromeClient()

        client.onGeolocationPermissionsShowPrompt("https://example.com", callback)

        verify(exactly = 1) { callback.invoke("https://example.com", false, false) }
    }

    @Test
    fun `onJsAlert suppresses alert dialog and returns true to prevent UI lock`() {
        val result = mockk<JsResult>(relaxed = true)
        val client = SecureWebChromeClient()

        val handled = client.onJsAlert(null, "https://example.com", "Alert Message", result)

        assertTrue("onJsAlert must return true to indicate it was handled", handled)
        verify(exactly = 1) { result.cancel() }
    }

    @Test
    fun `onJsConfirm suppresses confirm dialog and cancels result`() {
        val result = mockk<JsResult>(relaxed = true)
        val client = SecureWebChromeClient()

        val handled = client.onJsConfirm(null, "https://example.com", "Confirm?", result)

        assertTrue("onJsConfirm must return true to indicate it was handled", handled)
        verify(exactly = 1) { result.cancel() }
    }

    @Test
    fun `onJsPrompt suppresses prompt dialog and cancels result`() {
        val result = mockk<JsPromptResult>(relaxed = true)
        val client = SecureWebChromeClient()

        val handled = client.onJsPrompt(null, "https://example.com", "Enter value:", "default", result)

        assertTrue("onJsPrompt must return true to indicate it was handled", handled)
        verify(exactly = 1) { result.cancel() }
    }

    @Test
    fun `onJsBeforeUnload auto-confirms navigation without prompting user`() {
        val result = mockk<JsResult>(relaxed = true)
        val client = SecureWebChromeClient()

        val handled = client.onJsBeforeUnload(null, "https://example.com", "Leave page?", result)

        assertTrue("onJsBeforeUnload must return true", handled)
        verify(exactly = 1) { result.confirm() }
    }

    @Test
    fun `onCreateWindow returns false to disallow popup or new tab creation`() {
        val client = SecureWebChromeClient()

        val allowed = client.onCreateWindow(null, isDialog = false, isUserGesture = true, resultMsg = null)

        assertFalse("onCreateWindow must return false to strictly block popups", allowed)
    }

    @Test
    fun `onProgressChanged forwards progress updates to callback`() {
        var observedProgress = -1
        val client = SecureWebChromeClient(
            onProgressUpdate = { progress -> observedProgress = progress }
        )

        client.onProgressChanged(null, 75)

        assertEquals(75, observedProgress)
    }

    @Test
    fun `onReceivedTitle forwards non-blank title to callback`() {
        var observedTitle: String? = null
        val client = SecureWebChromeClient(
            onTitleReceived = { title -> observedTitle = title }
        )

        client.onReceivedTitle(null, "Dashboard V1")
        assertEquals("Dashboard V1", observedTitle)

        // Blank title should not trigger callback
        observedTitle = null
        client.onReceivedTitle(null, "   ")
        assertEquals(null, observedTitle)
    }
}
