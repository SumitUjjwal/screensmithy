package com.screensmithy.app.domain.model

/**
 * Ephemeral runtime presentation state and diagnostics.
 * Kept strictly isolated from persistent configuration preferences.
 */
data class RuntimeStatus(
    val lastLoadAttemptEpochMs: Long? = null,
    val lastLoadSuccess: Boolean? = null,
    val lastErrorMessage: String? = null,
    val isDeviceOwnerActive: Boolean = false,
    val isLockTaskPermitted: Boolean = false
)
