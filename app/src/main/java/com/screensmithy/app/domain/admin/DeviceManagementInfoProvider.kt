package com.screensmithy.app.domain.admin

import android.app.admin.DevicePolicyManager
import android.content.Context

/**
 * Provider interface for querying actual device policy status without coupling domain logic to Android platform internals.
 */
interface DeviceManagementInfoProvider {
    fun getInfo(): DeviceManagementInfo
}

/**
 * Android system implementation backed by [DevicePolicyManager].
 */
class SystemDeviceManagementInfoProvider(
    private val context: Context
) : DeviceManagementInfoProvider {

    override fun getInfo(): DeviceManagementInfo {
        val packageName = context.packageName
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager

        val isDeviceOwner = dpm?.isDeviceOwnerApp(packageName) ?: false
        val isProfileOwner = dpm?.isProfileOwnerApp(packageName) ?: false
        val isLockTaskPermitted = dpm?.isLockTaskPermitted(packageName) ?: false

        return DeviceManagementInfo(
            isDeviceOwner = isDeviceOwner,
            isProfileOwner = isProfileOwner,
            isLockTaskPermitted = isLockTaskPermitted,
            packageName = packageName
        )
    }
}
