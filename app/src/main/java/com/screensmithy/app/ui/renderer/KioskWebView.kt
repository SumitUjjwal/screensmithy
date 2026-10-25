package com.screensmithy.app.ui.renderer

import android.content.Context
import android.util.AttributeSet
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.webkit.WebViewAssetLoader
import com.screensmithy.app.domain.model.ContentSource
import com.screensmithy.app.domain.model.NavigationPolicy
import com.screensmithy.app.domain.renderer.RendererEvent
import com.screensmithy.app.domain.renderer.RendererState
import com.screensmithy.app.domain.url.UrlValidationResult
import com.screensmithy.app.domain.url.UrlValidator
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Reusable, hardened kiosk WebView host component.
 *
 * Enforces kiosk security boundaries:
 * - Decoupled from persistent DataStore; driven purely by domain [ContentSource] and [NavigationPolicy].
 * - App-private local HTML rendering restricted to [WebViewAssetLoader] (`https://appassets.androidplatform.net/imported_content/`).
 * - Strictly prohibits `file://` and `content://` schemes.
 * - Hardware acceleration, Safe Browsing, and autoplay enabled.
 * - Hardware permissions and JavaScript modal dialogs denied/suppressed.
 * - Observable [RendererState] and [RendererEvent] streams for host UI and future recovery engine.
 */
class KioskWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val _state = MutableStateFlow<RendererState>(RendererState.Idle)
    val state: StateFlow<RendererState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<RendererEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<RendererEvent> = _events.asSharedFlow()

    private var managedWebView: WebView? = null
    private var secureWebViewClient: SecureWebViewClient? = null
    private var secureWebChromeClient: SecureWebChromeClient? = null

    var currentSource: ContentSource = ContentSource.None
        private set

    var currentPolicy: NavigationPolicy = NavigationPolicy.DEFAULT
        private set

    init {
        initializeWebView()
    }

    private fun initializeWebView() {
        if (managedWebView != null) return

        val webView = WebView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }

        WebViewSecurityConfigurator.configure(webView, context)

        val assetLoader = buildAssetLoader()

        val client = SecureWebViewClient(
            assetLoader = assetLoader,
            configuredUrl = null,
            navigationPolicy = currentPolicy,
            onStateChange = { newState ->
                _state.value = newState
                if (newState is RendererState.RendererCrash) {
                    handleRendererCrash()
                }
            },
            onEvent = { event ->
                _events.tryEmit(event)
            }
        )

        val chromeClient = SecureWebChromeClient(
            onProgressUpdate = { progress ->
                val currentState = _state.value
                if (currentState is RendererState.Loading) {
                    _state.value = currentState.copy(progressPercent = progress)
                }
            },
            onTitleReceived = { title ->
                val currentState = _state.value
                if (currentState is RendererState.Ready) {
                    _state.value = currentState.copy(title = title)
                }
                _events.tryEmit(RendererEvent.TitleReceived(title))
            }
        )

        webView.webViewClient = client
        webView.webChromeClient = chromeClient

        addView(webView)
        managedWebView = webView
        secureWebViewClient = client
        secureWebChromeClient = chromeClient
    }

    /**
     * Loads the specified [ContentSource] applying the given [NavigationPolicy].
     */
    fun loadContent(source: ContentSource, policy: NavigationPolicy = NavigationPolicy.DEFAULT) {
        this.currentSource = source
        this.currentPolicy = policy

        val webView = managedWebView ?: run {
            initializeWebView()
            managedWebView ?: return
        }

        secureWebViewClient?.navigationPolicy = policy

        when (source) {
            is ContentSource.None -> {
                secureWebViewClient?.configuredUrl = null
                _state.value = RendererState.Idle
                webView.loadUrl(BLANK_URL)
            }

            is ContentSource.RemoteUrl -> {
                val validation = UrlValidator.validate(source.url)
                if (validation is UrlValidationResult.Invalid) {
                    _state.value = RendererState.MainFrameError(
                        errorCode = -1,
                        description = validation.userMessage,
                        failingUrl = source.url
                    )
                    return
                }

                val valid = validation as UrlValidationResult.Valid
                secureWebViewClient?.configuredUrl = valid.normalizedUrl
                _state.value = RendererState.Loading(valid.normalizedUrl, 0)
                webView.loadUrl(valid.normalizedUrl)
            }

            is ContentSource.LocalHtml -> {
                val relativePath = source.metadata.relativePath
                val file = File(context.filesDir, relativePath)
                if (!file.exists() || !file.isFile) {
                    _state.value = RendererState.MissingLocalContent(
                        relativePath = relativePath,
                        reason = "Imported HTML file was not found in app-private storage: ${file.absolutePath}"
                    )
                    return
                }

                // Strip root directory prefix if present to map to the asset loader path
                val subpath = relativePath
                    .removePrefix(LOCAL_STORAGE_DIR_NAME)
                    .removePrefix("/")

                val virtualUrl = "https://$ASSET_LOADER_DOMAIN$ASSET_LOADER_PATH$subpath"
                secureWebViewClient?.configuredUrl = virtualUrl
                _state.value = RendererState.Loading(virtualUrl, 0)
                webView.loadUrl(virtualUrl)
            }
        }
    }

    /**
     * Reloads the currently loaded [ContentSource].
     */
    fun reload() {
        loadContent(currentSource, currentPolicy)
    }

    /**
     * Pauses WebView timers and media processing when host Activity pauses.
     */
    fun pauseTimers() {
        managedWebView?.onPause()
        managedWebView?.pauseTimers()
    }

    /**
     * Resumes WebView timers and media processing when host Activity resumes.
     */
    fun resumeTimers() {
        managedWebView?.onResume()
        managedWebView?.resumeTimers()
    }

    /**
     * Completely destroys the managed WebView and unbinds listeners,
     * preventing memory leaks on Activity tear-down.
     */
    fun destroyRenderer() {
        managedWebView?.let { wv ->
            wv.stopLoading()
            wv.loadUrl(BLANK_URL)
            wv.clearHistory()
            wv.removeAllViews()
            removeView(wv)
            wv.destroy()
        }
        managedWebView = null
        secureWebViewClient = null
        secureWebChromeClient = null
        _state.value = RendererState.Idle
    }

    private fun handleRendererCrash() {
        // Tear down dead WebView instance so resources are reclaimed immediately
        managedWebView?.let { wv ->
            removeView(wv)
            wv.destroy()
        }
        managedWebView = null
        secureWebViewClient = null
        secureWebChromeClient = null
    }

    private fun buildAssetLoader(): WebViewAssetLoader {
        val importedHtmlDir = File(context.filesDir, LOCAL_STORAGE_DIR_NAME)
        return WebViewAssetLoader.Builder()
            .setDomain(ASSET_LOADER_DOMAIN)
            .addPathHandler(
                ASSET_LOADER_PATH,
                WebViewAssetLoader.InternalStoragePathHandler(context, importedHtmlDir)
            )
            .build()
    }

    internal fun getManagedWebViewForTesting(): WebView? = managedWebView

    companion object {
        const val ASSET_LOADER_DOMAIN: String = "appassets.androidplatform.net"
        const val ASSET_LOADER_PATH: String = "/imported_content/"
        const val LOCAL_STORAGE_DIR_NAME: String = "imported_html"
        private const val BLANK_URL: String = "about:blank"
    }
}
