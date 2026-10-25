package com.screensmithy.app.domain.admin

/**
 * Snapshot of actual system device management capabilities and runtime status.
 */
data class DeviceManagementInfo(
    val isDeviceOwner: Boolean,
    val isProfileOwner: Boolean,
    val isLockTaskPermitted: Boolean,
    val packageName: String
) {
    val modeDescription: String
        get() = if (isDeviceOwner) {
            "Managed Mode (Device Owner Active)"
        } else {
            "Standard Mode (Normal Android TV Installation)"
        }

    val capabilitiesSummary: String
        get() = if (isDeviceOwner) {
            "Hardware Lock Task mode, system key suppression, and reboot management are enabled."
        } else {
            "Running as a standard application. System keys (Home, Power) are governed by normal TV launcher policies."
        }
}
