package com.screensmithy.app.ui.renderer

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebViewFeature

/**
 * Centralized security configurator for ScreenSmithy WebViews.
 * Ensures all security constraints from MASTER_PROMPT.md are applied consistently in one place.
 */
object WebViewSecurityConfigurator {

    /**
     * Applies locked-down security settings to [webView].
     */
    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    fun configure(webView: WebView, context: Context) {
        val settings = webView.settings

        // 1. Scripting & Storage
        // Enabled for modern interactive dashboards, SPAs, and kiosk content
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = false

        // 2. Strict File Access Isolation
        // Prohibit all raw file:// and content:// access patterns
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.allowFileAccessFromFileURLs = false
        settings.allowUniversalAccessFromFileURLs = false

        // 3. Navigation, Window & Popup Isolation
        // Block popups and multiple windows to prevent kiosk escape
        settings.setSupportMultipleWindows(false)
        settings.javaScriptCanOpenWindowsAutomatically = false

        // 4. Transport & Mixed Content Integrity
        // Strictly forbid loading unencrypted HTTP subresources on HTTPS pages
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

        // 5. Media & Audio
        // Autoplay without user gesture (standard for digital signage / kiosks)
        settings.mediaPlaybackRequiresUserGesture = false

        // 6. Safe Browsing
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
            androidx.webkit.WebSettingsCompat.setSafeBrowsingEnabled(settings, true)
        }

        // 7. Cookies
        // Block 3rd-party cross-site tracking cookies
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, false)

        // 8. Hardware Acceleration & Focus
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true

        // 9. Absolutely NO addJavascriptInterface
        // (Do not inject any Java/Kotlin objects into untrusted webpage context)
    }
}
