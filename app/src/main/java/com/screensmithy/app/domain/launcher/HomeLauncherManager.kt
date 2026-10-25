package com.screensmithy.app.domain.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import com.screensmithy.app.domain.diagnostics.DefaultLauncherStatus

/**
 * Manages Home launcher detection, settings intent resolution,
 * and normal kiosk mode capability reporting for Android TV.
 */
class HomeLauncherManager(
    private val packageManager: PackageManager,
    private val packageName: String,
    private val intentResolver: ((Intent) -> Boolean)? = null,
    private val intentFactory: ((String) -> Intent)? = null
) {

    constructor(context: Context) : this(context.packageManager, context.packageName)

    /**
     * Inspects whether ScreenSmithy appears to be the current default Home application.
     */
    fun resolveDefaultHomeLauncher(): DefaultLauncherStatus {
        return try {
            val makeIntent: (String) -> Intent = intentFactory ?: { action -> Intent(action) }
            val homeIntent = makeIntent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
            }
            val resolveInfo = packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            val currentPkg = resolveInfo?.activityInfo?.packageName
            when {
                currentPkg == packageName -> DefaultLauncherStatus.Yes
                currentPkg == "android" || currentPkg?.contains("resolver", ignoreCase = true) == true -> {
                    DefaultLauncherStatus.No(null)
                }
                currentPkg != null -> DefaultLauncherStatus.No(currentPkg)
                else -> DefaultLauncherStatus.Unknown
            }
        } catch (_: Exception) {
            DefaultLauncherStatus.Unknown
        }
    }

    /**
     * Creates an [Intent] to open Android's Home / default app settings.
     * Probes supported platform actions in priority order:
     * 1. [Settings.ACTION_HOME_SETTINGS]
     * 2. [Settings.ACTION_SETTINGS]
     * 3. Home chooser intent fallback ([Intent.ACTION_MAIN] + [Intent.CATEGORY_HOME])
     */
    fun createHomeSettingsIntent(): Intent {
        val makeIntent: (String) -> Intent = intentFactory ?: { action -> Intent(action) }

        // Priority 1: Standard Android Home Settings
        val homeSettingsIntent = makeIntent(Settings.ACTION_HOME_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (isIntentResolvable(homeSettingsIntent)) {
            return homeSettingsIntent
        }

        // Priority 2: System Settings
        val systemSettingsIntent = makeIntent(Settings.ACTION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (isIntentResolvable(systemSettingsIntent)) {
            return systemSettingsIntent
        }

        // Priority 3: Fallback Home intent to trigger system chooser
        return makeIntent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Returns actionable guidance text explaining the current launcher status
     * and what the device administrator needs to do.
     */
    fun getLauncherGuidance(status: DefaultLauncherStatus): String {
        return when (status) {
            is DefaultLauncherStatus.Yes -> {
                "ScreenSmithy is configured as your default Home launcher. Pressing the remote Home button returns to ScreenSmithy."
            }
            is DefaultLauncherStatus.No -> {
                val current = status.currentDefaultPackage ?: "the standard system launcher"
                "Current default Home app is $current. In Normal Mode, Android TV does not allow apps to set themselves as default automatically. Click 'Configure Default Home App' (or press Home) to select ScreenSmithy."
            }
            is DefaultLauncherStatus.Unknown -> {
                "Default Home app could not be determined. Check your Android TV System Settings > Apps to review default launcher settings."
            }
        }
    }

    /**
     * Returns truthful, non-speculative limitations of running in Normal Kiosk Installation Mode.
     */
    fun getNormalModeLimitations(): List<String> {
        return listOf(
            "Cannot lock task without user exit; Device Owner mode is required for hardware lock task.",
            "Cannot block access to system settings or system power menus.",
            "Cannot intercept or disable OEM-branded remote buttons (e.g. Netflix, YouTube).",
            "Cannot silently force default Home launcher without explicit user confirmation in system settings.",
            "Screen remains awake and fullscreen immersive while ScreenSmithy is in the foreground."
        )
    }

    private fun isIntentResolvable(intent: Intent): Boolean {
        return intentResolver?.invoke(intent) ?: try {
            val resolveInfo = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            resolveInfo != null
        } catch (_: Exception) {
            false
        }
    }
}
