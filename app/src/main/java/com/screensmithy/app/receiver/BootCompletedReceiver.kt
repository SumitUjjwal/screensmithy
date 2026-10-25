package com.screensmithy.app.receiver

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.screensmithy.app.MainActivity

/**
 * BroadcastReceiver for handling device boot completion.
 *
 * Android Background Activity Launch Rules:
 * - In Normal Installation Mode, background activity launches are restricted by the Android OS (API 29+).
 *   ScreenSmithy relies on its registered [Intent.CATEGORY_HOME] launcher intent filter, allowing the
 *   Android TV OS to naturally start it as the foreground Home display on boot.
 * - In Managed Device Owner Mode, Device Owners are explicitly permitted to start activities from
 *   the background on boot to ensure unattended kiosk continuity.
 *
 * This receiver strictly avoids continuous background force-launch loops or redundant foreground services.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        val packageName = context.packageName
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        val isDeviceOwner = try {
            dpm?.isDeviceOwnerApp(packageName) == true
        } catch (_: Exception) {
            false
        }

        if (isDeviceOwner) {
            Log.i(TAG, "Device Owner boot completed; launching ScreenSmithy kiosk activity")
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            try {
                context.startActivity(launchIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch kiosk activity on boot: ${e.message}", e)
            }
        } else {
            Log.i(
                TAG,
                "Standard Mode boot completed. Kiosk launch is governed by Android TV Home launcher registration."
            )
        }
    }

    companion object {
        private const val TAG = "BootCompletedReceiver"
    }
}
