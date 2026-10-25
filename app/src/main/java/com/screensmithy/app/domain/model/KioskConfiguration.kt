package com.screensmithy.app.domain.model

/**
 * Root persistent configuration model for ScreenSmithy.
 * Note: Authentication and PIN materials are deliberately excluded and managed by
 * a dedicated security component with salted/derived storage in Phase 06.
 */
data class KioskConfiguration(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val isSetupCompleted: Boolean = false,
    val contentSource: ContentSource = ContentSource.None,
    val navigationPolicy: NavigationPolicy = NavigationPolicy(),
    val displayPreferences: DisplayPreferences = DisplayPreferences(),
    val retryPolicy: RetryPolicy = RetryPolicy(),
    val reloadOnNetworkRestored: Boolean = true
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1

        val DEFAULT = KioskConfiguration()
    }
}
