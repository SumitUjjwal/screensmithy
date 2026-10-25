package com.screensmithy.app.receiver

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * ScreenSmithy Device Administrator Receiver for Android enterprise Device Owner and managed kiosk operations.
 *
 * Manifest component: `com.screensmithy.app/com.screensmithy.app.receiver.KioskDeviceAdminReceiver`
 */
class KioskDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "ScreenSmithy Device Administrator enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.w(TAG, "ScreenSmithy Device Administrator disabled")
    }

    override fun onLockTaskModeEntering(context: Context, intent: Intent, pkg: String) {
        super.onLockTaskModeEntering(context, intent, pkg)
        Log.i(TAG, "ScreenSmithy entered Lock Task mode for package: $pkg")
    }

    override fun onLockTaskModeExiting(context: Context, intent: Intent) {
        super.onLockTaskModeExiting(context, intent)
        Log.i(TAG, "ScreenSmithy exited Lock Task mode")
    }

    companion object {
        private const val TAG = "KioskDeviceAdmin"

        /**
         * Resolves the [ComponentName] corresponding to [KioskDeviceAdminReceiver].
         */
        fun getComponentName(context: Context): ComponentName {
            return ComponentName(context.applicationContext, KioskDeviceAdminReceiver::class.java)
        }
    }
}
