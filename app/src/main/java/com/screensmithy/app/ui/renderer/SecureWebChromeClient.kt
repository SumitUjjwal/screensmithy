package com.screensmithy.app.ui.renderer

import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView

/**
 * Hardened [WebChromeClient] that enforces kiosk boundaries:
 * - Denies device hardware permissions (camera, microphone, geolocation) by default.
 * - Suppresses uncontrolled JavaScript modal dialogs to prevent kiosk lockups.
 * - Forwards loading progress and document titles to host observers.
 */
class SecureWebChromeClient(
    private val onProgressUpdate: (progressPercent: Int) -> Unit = {},
    private val onTitleReceived: (title: String) -> Unit = {}
) : WebChromeClient() {

    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        super.onProgressChanged(view, newProgress)
        onProgressUpdate(newProgress)
    }

    override fun onReceivedTitle(view: WebView?, title: String?) {
        super.onReceivedTitle(view, title)
        if (!title.isNullOrBlank()) {
            onTitleReceived(title)
        }
    }

    // 1. Hardware Permission Lockdown: Deny camera, mic, midi, and protected media
    override fun onPermissionRequest(request: PermissionRequest?) {
        request?.deny()
    }

    // 2. Geolocation Lockdown: Deny location requests
    override fun onGeolocationPermissionsShowPrompt(
        origin: String?,
        callback: GeolocationPermissions.Callback?
    ) {
        callback?.invoke(origin, false, false)
    }

    // 3. JavaScript Dialog Lockdown: Suppress alerts/confirms/prompts to prevent UI thread freezing
    override fun onJsAlert(
        view: WebView?,
        url: String?,
        message: String?,
        result: JsResult?
    ): Boolean {
        result?.cancel()
        return true // Handled: suppressed
    }

    override fun onJsConfirm(
        view: WebView?,
        url: String?,
        message: String?,
        result: JsResult?
    ): Boolean {
        result?.cancel()
        return true // Handled: suppressed
    }

    override fun onJsPrompt(
        view: WebView?,
        url: String?,
        message: String?,
        defaultValue: String?,
        result: JsPromptResult?
    ): Boolean {
        result?.cancel()
        return true // Handled: suppressed
    }

    override fun onJsBeforeUnload(
        view: WebView?,
        url: String?,
        message: String?,
        result: JsResult?
    ): Boolean {
        result?.confirm() // Allow navigation without prompting user
        return true
    }

    // 4. Popup & Multiple Windows Lockdown: Disallow opening new windows/tabs
    override fun onCreateWindow(
        view: WebView?,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: android.os.Message?
    ): Boolean {
        return false // Handled: denied
    }
}
