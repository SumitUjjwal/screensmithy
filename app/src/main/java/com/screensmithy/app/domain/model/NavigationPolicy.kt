package com.screensmithy.app.domain.model

/**
 * Navigation restriction modes for the content renderer.
 */
enum class NavigationMode {
    /**
     * Default mode: Only the host specified in the configured URL plus explicitly approved
     * redirect hosts ([NavigationPolicy.approvedRedirectHosts]) are permitted.
     */
    CONFIGURED_HOST_ONLY,

    /**
     * Strict allowlist mode: Only hosts explicitly enumerated in [NavigationPolicy.trustedHosts]
     * are permitted.
     */
    ALLOWLIST_ONLY,

    /**
     * Open navigation mode: Any valid HTTP or HTTPS host is permitted.
     * Note: Carries kiosk escape and untrusted content risk; accompanied by a risk warning.
     */
    ANY_HTTP_HOST
}

/**
 * Defines navigation access control policies for web presentation.
 *
 * @property mode Navigation restriction mode.
 * @property trustedHosts Explicitly allowlisted hosts (used when [mode] is [NavigationMode.ALLOWLIST_ONLY]).
 * @property approvedRedirectHosts Explicitly approved redirect hosts (used when [mode] is [NavigationMode.CONFIGURED_HOST_ONLY]).
 * @property includeSubdomains Whether subdomains of permitted hosts are automatically trusted (default: false, requiring exact host matches).
 */
data class NavigationPolicy(
    val mode: NavigationMode = NavigationMode.CONFIGURED_HOST_ONLY,
    val trustedHosts: Set<String> = emptySet(),
    val approvedRedirectHosts: Set<String> = emptySet(),
    val includeSubdomains: Boolean = false
) {
    /**
     * Warning message when open navigation mode is selected.
     */
    val riskWarning: String?
        get() = if (mode == NavigationMode.ANY_HTTP_HOST) {
            "Open navigation allows browsing to any external website. This may permit kiosk breakout or exposure to untrusted content."
        } else {
            null
        }

    companion object {
        val DEFAULT = NavigationPolicy()
    }
}
