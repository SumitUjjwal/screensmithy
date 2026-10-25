package com.screensmithy.app.domain.renderer

/**
 * Ephemeral events emitted by the WebView renderer to host listeners.
 */
sealed interface RendererEvent {
    /**
     * An attempted navigation or redirect was intercepted and blocked by [NavigationPolicy].
     */
    data class NavigationBlocked(
        val targetUrl: String,
        val reason: String
    ) : RendererEvent

    /**
     * An attempted external application or custom scheme launch was blocked.
     */
    data class ExternalAppLaunchBlocked(
        val targetUrl: String
    ) : RendererEvent

    /**
     * Document title was updated.
     */
    data class TitleReceived(
        val title: String
    ) : RendererEvent
}
