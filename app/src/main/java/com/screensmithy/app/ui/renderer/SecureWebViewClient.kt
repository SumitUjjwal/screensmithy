package com.screensmithy.app.ui.renderer

import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import com.screensmithy.app.domain.model.NavigationPolicy
import com.screensmithy.app.domain.renderer.RendererEvent
import com.screensmithy.app.domain.renderer.RendererState
import com.screensmithy.app.domain.url.NavigationDecision
import com.screensmithy.app.domain.url.NavigationPolicyEvaluator
import com.screensmithy.app.domain.url.NavigationViolation
import java.util.Locale

/**
 * Hardened [WebViewClient] enforcing kiosk navigation boundaries, TLS certificate validation,
 * local asset sandbox isolation via [WebViewAssetLoader], and distinction between main-frame
 * and subresource errors.
 */
class SecureWebViewClient(
    private val assetLoader: WebViewAssetLoader,
    var configuredUrl: String? = null,
    var navigationPolicy: NavigationPolicy = NavigationPolicy.DEFAULT,
    private val onStateChange: (RendererState) -> Unit,
    private val onEvent: (RendererEvent) -> Unit
) : WebViewClient() {

    internal var hasMainFrameError: Boolean = false
        private set

    /**
     * Intercepts requests destined for the app-private local content virtual domain
     * (e.g. `https://appassets.androidplatform.net/imported_content/`).
     * Prevents any raw `file://` or `content://` loading.
     */
    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? {
        val url = request?.url ?: return null
        val intercepted = assetLoader.shouldInterceptRequest(url)
        if (intercepted != null) {
            return intercepted
        }
        return super.shouldInterceptRequest(view, request)
    }

    /**
     * Backward-compatible navigation override for API < 24.
     */
    @Deprecated("Deprecated in Java", ReplaceWith("shouldOverrideUrlLoading(view, request)"))
    @Suppress("DEPRECATION", "OverridingDeprecatedMember")
    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
        if (url.isNullOrBlank()) return true
        return handleNavigation(url)
    }

    /**
     * Navigation override for API >= 24.
     * Evaluates navigation against [NavigationPolicy] and blocks external application launch schemes.
     */
    override fun shouldOverrideUrlLoading(
        view: WebView?,
        request: WebResourceRequest?
    ): Boolean {
        val url = request?.url?.toString()
        if (url.isNullOrBlank()) return true
        return handleNavigation(url)
    }

    /**
     * Evaluates navigation policy for [targetUrl].
     *
     * @return true to cancel/block navigation, false to allow WebView to proceed.
     */
    internal fun handleNavigation(targetUrl: String): Boolean {
        val decision = NavigationPolicyEvaluator.evaluate(
            targetUrl = targetUrl,
            configuredUrl = configuredUrl,
            policy = navigationPolicy
        )

        return when (decision) {
            is NavigationDecision.Allowed -> {
                // Navigation is permitted within configured kiosk boundaries
                false
            }

            is NavigationDecision.Blocked -> {
                if (isExternalAppScheme(targetUrl) || decision.violation == NavigationViolation.UNSUPPORTED_SCHEME) {
                    onEvent(RendererEvent.ExternalAppLaunchBlocked(targetUrl))
                }
                onEvent(RendererEvent.NavigationBlocked(targetUrl, decision.reason))
                // Cancel navigation to prevent kiosk escape
                true
            }
        }
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        hasMainFrameError = false
        if (!url.isNullOrBlank()) {
            onStateChange(RendererState.Loading(url = url, progressPercent = 0))
        }
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        if (!hasMainFrameError && !url.isNullOrBlank()) {
            onStateChange(RendererState.Ready(url = url, title = view?.title))
        }
    }

    /**
     * Handles connection and network errors.
     * Crucially differentiates between main-frame document failures and subresource failures.
     */
    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?
    ) {
        super.onReceivedError(view, request, error)
        if (request?.isForMainFrame == true) {
            hasMainFrameError = true
            val errorCode = error?.errorCode ?: -1
            val description = error?.description?.toString() ?: "Main-frame network failure"
            val failingUrl = request.url?.toString() ?: ""
            onStateChange(
                RendererState.MainFrameError(
                    errorCode = errorCode,
                    description = description,
                    failingUrl = failingUrl
                )
            )
        }
        // Subresource failures (e.g. missing tracking pixel, CDN image) are deliberately not fatal
    }

    /**
     * Handles HTTP client and server error status codes (4xx / 5xx) on the main document.
     */
    override fun onReceivedHttpError(
        view: WebView?,
        request: WebResourceRequest?,
        errorResponse: WebResourceResponse?
    ) {
        super.onReceivedHttpError(view, request, errorResponse)
        if (request?.isForMainFrame == true) {
            hasMainFrameError = true
            val statusCode = errorResponse?.statusCode ?: 0
            val reasonPhrase = errorResponse?.reasonPhrase ?: "HTTP Error"
            val failingUrl = request.url?.toString() ?: ""
            onStateChange(
                RendererState.HttpError(
                    statusCode = statusCode,
                    reasonPhrase = reasonPhrase,
                    failingUrl = failingUrl
                )
            )
        }
    }

    /**
     * Handles SSL/TLS certificate validation failures.
     * FAIL-CLOSED: strictly cancels the request; never proceeds through untrusted certificates.
     */
    override fun onReceivedSslError(
        view: WebView?,
        handler: SslErrorHandler?,
        error: SslError?
    ) {
        handler?.cancel()
        hasMainFrameError = true

        val failingUrl = error?.url ?: ""
        val primaryCode = error?.primaryError
        val description = when (primaryCode) {
            SslError.SSL_NOTYETVALID -> "Certificate is not yet valid"
            SslError.SSL_EXPIRED -> "Certificate has expired"
            SslError.SSL_IDMISMATCH -> "Hostname mismatch in certificate"
            SslError.SSL_UNTRUSTED -> "Certificate authority is untrusted"
            SslError.SSL_DATE_INVALID -> "Certificate date is invalid"
            SslError.SSL_INVALID -> "Certificate is invalid"
            else -> "SSL/TLS connection error"
        }

        onStateChange(
            RendererState.TlsError(
                failingUrl = failingUrl,
                errorDescription = description,
                primaryErrorCode = primaryCode
            )
        )
    }

    /**
     * Handles renderer process termination (OOM killer or Chromium crash).
     */
    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.O)
    override fun onRenderProcessGone(
        view: WebView?,
        detail: RenderProcessGoneDetail?
    ): Boolean {
        hasMainFrameError = true
        val didCrash = detail?.didCrash() ?: false
        onStateChange(RendererState.RendererCrash(didCrash = didCrash))
        // Return true to indicate the host handled the termination and prevent OS process kill
        return true
    }

    private fun isExternalAppScheme(url: String): Boolean {
        val colonIndex = url.indexOf(':')
        if (colonIndex <= 0) return false
        val scheme = url.substring(0, colonIndex).lowercase(Locale.ROOT)
        return scheme !in WEB_SCHEMES
    }

    companion object {
        private val WEB_SCHEMES = setOf("http", "https", "about")
    }
}
