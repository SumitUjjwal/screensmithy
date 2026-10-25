package com.screensmithy.app.domain.devicepolicy

/**
 * Encapsulates the current Lock Task Mode state of the kiosk application.
 */
sealed interface LockTaskStatus {
    /**
     * Lock Task is allowlisted and eligible, but not currently active.
     */
    data object Inactive : LockTaskStatus

    /**
     * Managed Lock Task mode is currently active (LOCK_TASK_MODE_LOCKED).
     */
    data object Locked : LockTaskStatus

    /**
     * Standard screen pinning mode is currently active (LOCK_TASK_MODE_PINNED).
     */
    data object Pinned : LockTaskStatus

    /**
     * Lock Task mode is unavailable or not supported in current environment.
     */
    data class Unavailable(val reason: String) : LockTaskStatus

    val isLocked: Boolean get() = this is Locked
}
