package com.screensmithy.app.domain.model

/**
 * Navigation restriction modes for the content renderer.
 */
enum class NavigationMode {
    /**
     * Only the host specified in the configured URL (and its initial redirects) is permitted.
     */
    CONFIGURED_HOST_ONLY,

    /**
     * Only hosts explicitly enumerated in [NavigationPolicy.trustedHosts] are permitted.
     */
    ALLOWLIST_ONLY,

    /**
     * Any valid HTTP(S) host is permitted.
     */
    ANY_HTTP_HOST
}

/**
 * Defines navigation access control policies for web presentation.
 */
data class NavigationPolicy(
    val mode: NavigationMode = NavigationMode.CONFIGURED_HOST_ONLY,
    val trustedHosts: Set<String> = emptySet()
)
