package com.screensmithy.app.data.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import androidx.webkit.WebViewCompat
import com.screensmithy.app.domain.admin.DeviceManagementInfoProvider
import com.screensmithy.app.domain.diagnostics.DefaultLauncherStatus
import com.screensmithy.app.domain.diagnostics.NetworkDiagnosticStatus
import com.screensmithy.app.domain.diagnostics.SecretSanitizer
import com.screensmithy.app.domain.diagnostics.SystemDiagnosticInspector
import com.screensmithy.app.domain.diagnostics.SystemDiagnosticSnapshot
import com.screensmithy.app.domain.model.ContentSource
import com.screensmithy.app.domain.model.RuntimeStatus
import com.screensmithy.app.domain.repository.ConfigurationRepository

class AndroidSystemDiagnosticInspector(
    private val context: Context,
    private val configRepository: ConfigurationRepository,
    private val deviceManagementInfoProvider: DeviceManagementInfoProvider,
    private val runtimeStatusProvider: () -> RuntimeStatus = { RuntimeStatus() },
    private val launcherChecker: (() -> DefaultLauncherStatus)? = null
) : SystemDiagnosticInspector {

    override suspend fun inspect(): SystemDiagnosticSnapshot {
        val config = configRepository.getConfiguration()
        val runtime = runtimeStatusProvider()
        val mgmtInfo = deviceManagementInfoProvider.getInfo()

        val packageManager = context.packageManager
        val packageName = context.packageName

        // App version
        val (versionName, versionCode) = try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            val vName = pInfo.versionName ?: "Unknown"
            val vCode = PackageInfoCompat.getLongVersionCode(pInfo)
            vName to vCode
        } catch (_: Exception) {
            "Unknown" to 0L
        }

        // TV Leanback Feature
        val isLeanback = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

        // OS and Device Model
        val osVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"

        // WebView package version
        val webViewPackage = try {
            WebViewCompat.getCurrentWebViewPackage(context)?.let {
                "${it.packageName} v${it.versionName}"
            } ?: "System Default"
        } catch (_: Exception) {
            "System Default"
        }

        // Lock Task Mode State
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val lockTaskState = when (activityManager?.lockTaskModeState) {
            ActivityManager.LOCK_TASK_MODE_NONE -> "None (Not active)"
            ActivityManager.LOCK_TASK_MODE_LOCKED -> "Locked (Device Owner)"
            ActivityManager.LOCK_TASK_MODE_PINNED -> "Pinned (Screen Pinning)"
            else -> "Unavailable"
        }

        // Default Launcher check
        val defaultLauncher = launcherChecker?.invoke() ?: com.screensmithy.app.domain.launcher.HomeLauncherManager(context).resolveDefaultHomeLauncher()

        // Network status check
        val networkStatus = try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val activeNet = cm?.activeNetwork
            val caps = cm?.getNetworkCapabilities(activeNet)

            val isConnected = caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val isValidated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            val transport = when {
                caps == null -> "None"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
                else -> "Other"
            }
            NetworkDiagnosticStatus(isConnected, transport, isValidated)
        } catch (_: Exception) {
            NetworkDiagnosticStatus(isConnected = false, transportType = "Unknown", isValidated = false)
        }

        // Sanitized content identifier
        val (sourceType, sanitizedIdentifier) = when (val s = config.contentSource) {
            is ContentSource.RemoteUrl -> "Remote Web URL" to SecretSanitizer.sanitizeUrl(s.url)
            is ContentSource.LocalHtml -> "Local HTML Document" to s.metadata.displayName
            is ContentSource.None -> "None (Setup Incomplete)" to "No content configured"
        }

        return SystemDiagnosticSnapshot(
            timestampEpochMs = System.currentTimeMillis(),
            appVersion = versionName,
            appVersionCode = versionCode,
            osVersion = osVersion,
            deviceModel = deviceModel,
            isLeanbackTv = isLeanback,
            webViewPackage = webViewPackage,
            operatingMode = if (mgmtInfo.isDeviceOwner) "Device Owner Mode" else "Standard Normal Mode",
            isDeviceOwner = mgmtInfo.isDeviceOwner,
            isProfileOwner = mgmtInfo.isProfileOwner,
            lockTaskState = lockTaskState,
            defaultHomeLauncher = defaultLauncher,
            networkStatus = networkStatus,
            contentSourceType = sourceType,
            sanitizedContentIdentifier = sanitizedIdentifier,
            isSetupCompleted = config.isSetupCompleted,
            navigationMode = config.navigationPolicy.mode.name,
            keepScreenAwake = config.displayPreferences.keepScreenAwake,
            immersiveSticky = config.displayPreferences.immersiveSticky,
            reloadOnNetworkRestored = config.reloadOnNetworkRestored,
            lastLoadAttemptEpochMs = runtime.lastLoadAttemptEpochMs,
            lastLoadSuccess = runtime.lastLoadSuccess,
            lastErrorMessage = runtime.lastErrorMessage?.let { SecretSanitizer.sanitizeMessage(it) }
        )
    }
}
