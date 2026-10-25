package com.screensmithy.app.domain.renderer

/**
 * State machine representing the presentation and health status of the WebView content renderer.
 */
sealed interface RendererState {
    /**
     * Initial uninitialized or reset state.
     */
    data object Idle : RendererState

    /**
     * Content is actively loading.
     *
     * @property url The URL or local content address being loaded.
     * @property progressPercent Current progress (0-100).
     */
    data class Loading(
        val url: String,
        val progressPercent: Int = 0
    ) : RendererState

    /**
     * Main-frame content loaded successfully and is ready for display.
     *
     * @property url Loaded canonical URL.
     * @property title Document title if available.
     */
    data class Ready(
        val url: String,
        val title: String? = null
    ) : RendererState

    /**
     * Main-frame connection or network failure (e.g. host unreachable, connection refused).
     * Note: Subresource failures (e.g. failed tracking pixel) do NOT trigger this state.
     */
    data class MainFrameError(
        val errorCode: Int,
        val description: String,
        val failingUrl: String
    ) : RendererState

    /**
     * Server returned an HTTP client or server error (HTTP 4xx / 5xx) on the main document.
     */
    data class HttpError(
        val statusCode: Int,
        val reasonPhrase: String,
        val failingUrl: String
    ) : RendererState

    /**
     * TLS/SSL certificate validation failure.
     * Handled fail-closed: certificates are never ignored.
     */
    data class TlsError(
        val failingUrl: String,
        val errorDescription: String,
        val primaryErrorCode: Int? = null
    ) : RendererState

    /**
     * The referenced app-private HTML document could not be located on storage.
     */
    data class MissingLocalContent(
        val relativePath: String,
        val reason: String
    ) : RendererState

    /**
     * The underlying WebView Chromium render process was terminated (e.g. OOM or crash).
     */
    data class RendererCrash(
        val didCrash: Boolean
    ) : RendererState
}
