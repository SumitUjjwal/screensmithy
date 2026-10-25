package com.screensmithy.app.domain.model

/**
 * Type-safe definition of the kiosk presentation content source.
 */
sealed interface ContentSource {
    /**
     * Initial state where no content has been configured yet.
     */
    data object None : ContentSource

    /**
     * Standalone local HTML file imported securely into app-private storage.
     */
    data class LocalHtml(
        val metadata: LocalHtmlMetadata
    ) : ContentSource

    /**
     * Remote or intranet web resource reachable via HTTP or HTTPS.
     */
    data class RemoteUrl(
        val url: String
    ) : ContentSource
}
