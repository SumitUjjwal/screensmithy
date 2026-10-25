package com.screensmithy.app.data.devicepolicy

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.screensmithy.app.MainActivity
import com.screensmithy.app.domain.devicepolicy.DeviceOwnerPolicyManager
import com.screensmithy.app.domain.devicepolicy.LockTaskStatus
import com.screensmithy.app.domain.devicepolicy.PolicyExecutionResult
import com.screensmithy.app.receiver.KioskDeviceAdminReceiver

/**
 * Android system implementation of [DeviceOwnerPolicyManager] backed by [DevicePolicyManager]
 * and [ActivityManager]. Supports dependency injection for comprehensive JVM testing.
 */
class AndroidDeviceOwnerPolicyManager(
    private val context: Context,
    private val dpmProvider: () -> DevicePolicyManager? = {
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
    },
    private val amProvider: () -> ActivityManager? = {
        context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    },
    private val adminComponent: ComponentName = KioskDeviceAdminReceiver.getComponentName(context),
    private val targetPackageName: String = context.packageName
) : DeviceOwnerPolicyManager {

    private val dpm: DevicePolicyManager? get() = dpmProvider()
    private val am: ActivityManager? get() = amProvider()

    override val isDeviceOwner: Boolean
        get() = try {
            dpm?.isDeviceOwnerApp(targetPackageName) == true
        } catch (_: Exception) {
            false
        }

    override val isProfileOwner: Boolean
        get() = try {
            dpm?.isProfileOwnerApp(targetPackageName) == true
        } catch (_: Exception) {
            false
        }

    override val isLockTaskPermitted: Boolean
        get() = try {
            dpm?.isLockTaskPermitted(targetPackageName) == true
        } catch (_: Exception) {
            false
        }

    override val lockTaskStatus: LockTaskStatus
        get() {
            val state = try {
                am?.lockTaskModeState
            } catch (_: Exception) {
                null
            }
            return when (state) {
                ActivityManager.LOCK_TASK_MODE_LOCKED -> LockTaskStatus.Locked
                ActivityManager.LOCK_TASK_MODE_PINNED -> LockTaskStatus.Pinned
                ActivityManager.LOCK_TASK_MODE_NONE -> {
                    if (canEnterLockTask()) {
                        LockTaskStatus.Inactive
                    } else {
                        LockTaskStatus.Unavailable("Device Owner not active or app not allowlisted for Lock Task")
                    }
                }
                else -> LockTaskStatus.Unavailable("Unable to determine Lock Task state")
            }
        }

    override fun canEnterLockTask(): Boolean {
        return isDeviceOwner && isLockTaskPermitted
    }

    override fun allowlistLockTaskPackages(allowed: Boolean): PolicyExecutionResult {
        if (!isDeviceOwner) {
            return PolicyExecutionResult.NotEligible("Device Owner privileges are not active.")
        }
        val manager = dpm ?: return PolicyExecutionResult.Failure("DevicePolicyManager unavailable")
        return try {
            val packages = if (allowed) arrayOf(targetPackageName) else emptyArray()
            manager.setLockTaskPackages(adminComponent, packages)

            // Verify policy applied
            val isPermitted = manager.isLockTaskPermitted(targetPackageName)
            if (allowed == isPermitted) {
                PolicyExecutionResult.Success(
                    if (allowed) "ScreenSmithy allowlisted for Lock Task mode" else "Lock Task allowlist cleared"
                )
            } else {
                PolicyExecutionResult.Failure("Verification failed after updating Lock Task packages")
            }
        } catch (e: Exception) {
            PolicyExecutionResult.Failure("Failed to update Lock Task allowlist: ${e.message}", e)
        }
    }

    override fun configureKioskLockTaskFeatures(enableSystemInfo: Boolean): PolicyExecutionResult {
        if (!isDeviceOwner) {
            return PolicyExecutionResult.NotEligible("Device Owner privileges are not active.")
        }
        val manager = dpm ?: return PolicyExecutionResult.Failure("DevicePolicyManager unavailable")
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val flags = if (enableSystemInfo) {
                    DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO
                } else {
                    DevicePolicyManager.LOCK_TASK_FEATURE_NONE
                }
                manager.setLockTaskFeatures(adminComponent, flags)
                PolicyExecutionResult.Success("Lock Task features configured (flags: $flags)")
            } else {
                PolicyExecutionResult.Success("Lock Task features configuration skipped on API < 28")
            }
        } catch (e: Exception) {
            PolicyExecutionResult.Failure("Failed to configure Lock Task features: ${e.message}", e)
        }
    }

    override fun setKeyguardAndStatusBarDisabled(disabled: Boolean): PolicyExecutionResult {
        if (!isDeviceOwner) {
            return PolicyExecutionResult.NotEligible("Device Owner privileges are not active.")
        }
        val manager = dpm ?: return PolicyExecutionResult.Failure("DevicePolicyManager unavailable")
        return try {
            manager.setKeyguardDisabled(adminComponent, disabled)
            manager.setStatusBarDisabled(adminComponent, disabled)
            PolicyExecutionResult.Success("Keyguard and status bar disabled: $disabled")
        } catch (e: Exception) {
            PolicyExecutionResult.Failure("Failed to update keyguard and status bar policy: ${e.message}", e)
        }
    }

    override fun setPersistentPreferredHome(enabled: Boolean): PolicyExecutionResult {
        if (!isDeviceOwner) {
            return PolicyExecutionResult.NotEligible("Device Owner privileges are not active.")
        }
        val manager = dpm ?: return PolicyExecutionResult.Failure("DevicePolicyManager unavailable")
        return try {
            if (enabled) {
                val filter = IntentFilter(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    addCategory(Intent.CATEGORY_DEFAULT)
                }
                val activityComponent = ComponentName(targetPackageName, MainActivity::class.java.name)
                manager.addPersistentPreferredActivity(adminComponent, filter, activityComponent)
                PolicyExecutionResult.Success("ScreenSmithy registered as persistent Home activity")
            } else {
                manager.clearPackagePersistentPreferredActivities(adminComponent, targetPackageName)
                PolicyExecutionResult.Success("Persistent Home activity cleared")
            }
        } catch (e: Exception) {
            PolicyExecutionResult.Failure("Failed to update persistent Home activity: ${e.message}", e)
        }
    }

    override fun applyManagedKioskPolicies(): PolicyExecutionResult {
        if (!isDeviceOwner) {
            return PolicyExecutionResult.NotEligible("Device Owner privileges are not active.")
        }
        val allowlistResult = allowlistLockTaskPackages(true)
        if (!allowlistResult.isSuccess) return allowlistResult

        val featuresResult = configureKioskLockTaskFeatures(enableSystemInfo = false)
        if (!featuresResult.isSuccess) return featuresResult

        val keyguardResult = setKeyguardAndStatusBarDisabled(true)
        if (!keyguardResult.isSuccess) return keyguardResult

        val homeResult = setPersistentPreferredHome(true)
        if (!homeResult.isSuccess) return homeResult

        return PolicyExecutionResult.Success("All managed kiosk policies applied successfully")
    }

    override fun clearManagedKioskPolicies(): PolicyExecutionResult {
        if (!isDeviceOwner) {
            return PolicyExecutionResult.NotEligible("Device Owner privileges are not active.")
        }
        setPersistentPreferredHome(false)
        setKeyguardAndStatusBarDisabled(false)
        allowlistLockTaskPackages(false)
        return PolicyExecutionResult.Success("Managed kiosk policies cleared (Device Owner remains active)")
    }

    override fun enterLockTask(activity: Activity): PolicyExecutionResult {
        val currentLockState = try {
            am?.lockTaskModeState
        } catch (_: Exception) {
            null
        }
        if (currentLockState == ActivityManager.LOCK_TASK_MODE_LOCKED) {
            return PolicyExecutionResult.Success("Lock Task mode is already active")
        }

        if (!canEnterLockTask()) {
            return PolicyExecutionResult.NotEligible(
                "App is not eligible for Lock Task mode (Device Owner and allowlist required)"
            )
        }

        return try {
            activity.startLockTask()
            PolicyExecutionResult.Success("Lock Task mode initiated")
        } catch (e: Exception) {
            PolicyExecutionResult.Failure("Failed to start Lock Task: ${e.message}", e)
        }
    }

    override fun exitLockTask(activity: Activity): PolicyExecutionResult {
        val currentLockState = try {
            am?.lockTaskModeState
        } catch (_: Exception) {
            null
        }
        if (currentLockState == ActivityManager.LOCK_TASK_MODE_NONE) {
            return PolicyExecutionResult.Success("Lock Task mode is not active")
        }

        return try {
            activity.stopLockTask()
            PolicyExecutionResult.Success("Lock Task mode stopped for maintenance")
        } catch (e: Exception) {
            PolicyExecutionResult.Failure("Failed to stop Lock Task: ${e.message}", e)
        }
    }
}
