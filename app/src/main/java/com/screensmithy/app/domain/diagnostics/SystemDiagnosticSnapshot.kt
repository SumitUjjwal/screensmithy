package com.screensmithy.app.domain.diagnostics

/**
 * Encapsulates the actual runtime system and kiosk diagnostic state.
 */
data class SystemDiagnosticSnapshot(
    val timestampEpochMs: Long,
    val appVersion: String,
    val appVersionCode: Long,
    val osVersion: String,
    val deviceModel: String,
    val isLeanbackTv: Boolean,
    val webViewPackage: String,
    val operatingMode: String,
    val isDeviceOwner: Boolean,
    val isProfileOwner: Boolean,
    val lockTaskState: String,
    val defaultHomeLauncher: DefaultLauncherStatus,
    val networkStatus: NetworkDiagnosticStatus,
    val contentSourceType: String,
    val sanitizedContentIdentifier: String,
    val isSetupCompleted: Boolean,
    val navigationMode: String,
    val keepScreenAwake: Boolean,
    val immersiveSticky: Boolean,
    val reloadOnNetworkRestored: Boolean,
    val lastLoadAttemptEpochMs: Long? = null,
    val lastLoadSuccess: Boolean? = null,
    val lastErrorMessage: String? = null
)

sealed interface DefaultLauncherStatus {
    data object Yes : DefaultLauncherStatus
    data class No(val currentDefaultPackage: String?) : DefaultLauncherStatus
    data object Unknown : DefaultLauncherStatus
}

data class NetworkDiagnosticStatus(
    val isConnected: Boolean,
    val transportType: String, // "Wi-Fi", "Ethernet", "Cellular", "None", "Other"
    val isValidated: Boolean
)
