package com.screensmithy.app.domain.devicepolicy

import android.app.Activity

/**
 * Contract for querying and managing Android enterprise Device Owner and Lock Task policies.
 * Decoupled from Android platform internals to allow deterministic unit testing.
 */
interface DeviceOwnerPolicyManager {
    val isDeviceOwner: Boolean
    val isProfileOwner: Boolean
    val isLockTaskPermitted: Boolean
    val lockTaskStatus: LockTaskStatus

    /**
     * Returns true only when the application is confirmed eligible to enter managed Lock Task mode.
     */
    fun canEnterLockTask(): Boolean

    /**
     * Allowlist ScreenSmithy for Lock Task mode via [android.app.admin.DevicePolicyManager.setLockTaskPackages].
     */
    fun allowlistLockTaskPackages(allowed: Boolean = true): PolicyExecutionResult

    /**
     * Configures Lock Task features (system UI, keyguard, global actions).
     */
    fun configureKioskLockTaskFeatures(enableSystemInfo: Boolean = false): PolicyExecutionResult

    /**
     * Configures keyguard and status bar disabling for unattended kiosk displays.
     */
    fun setKeyguardAndStatusBarDisabled(disabled: Boolean): PolicyExecutionResult

    /**
     * Configures ScreenSmithy as persistent preferred Home activity via [android.app.admin.DevicePolicyManager.addPersistentPreferredActivity].
     */
    fun setPersistentPreferredHome(enabled: Boolean): PolicyExecutionResult

    /**
     * Applies all supported managed kiosk policies: allowlisting, Lock Task features, keyguard/status bar, and persistent Home.
     */
    fun applyManagedKioskPolicies(): PolicyExecutionResult

    /**
     * Clears managed kiosk policies (restoring standard Home and system bars) without stripping Device Owner privileges.
     */
    fun clearManagedKioskPolicies(): PolicyExecutionResult

    /**
     * Safely initiates Lock Task mode on the provided activity if eligible.
     */
    fun enterLockTask(activity: Activity): PolicyExecutionResult

    /**
     * Safely terminates Lock Task mode on the provided activity for administrative maintenance.
     */
    fun exitLockTask(activity: Activity): PolicyExecutionResult
}
