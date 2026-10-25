# ScreenSmithy — Android Enterprise Device Owner Setup & Managed Kiosk Guide

This document describes how to provision and manage ScreenSmithy as an Android Enterprise **Device Owner** (Managed Mode) for dedicated, unattended Android TV digital signage and kiosk deployments.

---

## 1. Operating Modes Overview

ScreenSmithy supports two distinct operating architectures:

| Mode | Provisioning Requirement | Lock Task Mode | Home Launcher | Remote Button Interception |
|---|---|---|---|---|
| **Normal Installation Mode** (Phase 09) | Standard APK install via ADB, USB, or app store. No factory reset needed. | Not available (system screen pinning only). | Standard Android TV Home launcher via `Settings.ACTION_HOME_SETTINGS`. | System keys (Home, Power, OEM branded buttons) governed by TV OS. |
| **Device Owner Managed Mode** (Phase 10) | Enterprise provisioning via ADB or QR code on unprovisioned/factory-reset device. | Fully locked (`LOCK_TASK_MODE_LOCKED`), navigation bars & notifications suppressed. | Persistent default Home launcher via `DevicePolicyManager.addPersistentPreferredActivity`. | System Home & Back buttons locked into ScreenSmithy; OEM buttons suppressed in Lock Task. |

---

## 2. Component Identifiers

Always use these exact component names when executing platform or ADB commands:

- **Application ID / Package**: `com.screensmithy.app`
- **Device Admin Receiver**: `com.screensmithy.app.receiver.KioskDeviceAdminReceiver`
- **Full Admin Component**: `com.screensmithy.app/com.screensmithy.app.receiver.KioskDeviceAdminReceiver`
- **Main Kiosk Activity**: `com.screensmithy.app/.MainActivity`

---

## 3. Prerequisites

1. **Android TV Device**: Running Android 6.0 (API 23) or higher (ScreenSmithy targets Android 14 / API 34+).
2. **Developer Options & ADB Enabled**:
   - Navigate to `Settings > System > About > Android TV OS build` and press `OK` 7 times to enable Developer options.
   - Go to `Settings > System > Developer options` and enable **USB debugging** (and **Network debugging** if connecting over Wi-Fi).
3. **ADB Client on Workstation**:
   - Connect via USB or Wi-Fi:
     ```bash
     adb connect <TV_IP_ADDRESS>:5555
     adb devices
     ```

---

## 4. Critical Account & Factory-Reset Warning

> [!WARNING]
> **Android Platform Security Rule**:
> Android strictly forbids setting a Device Owner via ADB if **any user accounts** already exist on the device.
>
> If you run `dpm set-device-owner` on a device with configured Google, OEM, or application accounts, the system will immediately fail with:
> ```text
> java.lang.IllegalStateException: Not allowed to set the device owner because there are already some accounts on the device
> ```

### Pre-Provisioning Account Check
Before attempting to provision Device Owner, verify accounts on User 0:
```bash
adb shell dumpsys account | grep -E "Account \{|User 0:"
```

- If **no accounts** are returned: proceed directly to Step 5.
- If **accounts exist**:
  1. Remove all accounts manually via `Settings > Accounts & Sign-in > [Account] > Remove account`.
  2. If an account cannot be removed (common on Google TV setups where the primary account is locked to the launcher), perform a **Factory Reset**:
     ```bash
     adb shell am broadcast -a android.intent.action.MASTER_CLEAR
     # Or navigate to Settings > System > About > Reset > Factory reset
     ```
  3. Complete initial TV setup **without adding a Google account** (or connect ADB during initial wizard).

---

## 5. Device Owner Provisioning Procedure

### Step 5.1: Install the ScreenSmithy APK
```bash
adb install -r -t app-debug.apk
```

### Step 5.2: Set ScreenSmithy as Device Owner
Execute the platform Device Policy Manager command:
```bash
adb shell dpm set-device-owner com.screensmithy.app/com.screensmithy.app.receiver.KioskDeviceAdminReceiver
```

Expected output on success:
```text
Success: Device owner set to package ComponentInfo{com.screensmithy.app/com.screensmithy.app.receiver.KioskDeviceAdminReceiver}
Active admin set to component {com.screensmithy.app/com.screensmithy.app.receiver.KioskDeviceAdminReceiver}
```

---

## 6. Verification Commands

Verify that ScreenSmithy has been successfully recognized as the active Device Owner:

### Check Active Device Owners
```bash
adb shell dpm list-owners
```
Expected output:
```text
Device Owner:
  admin=ComponentInfo{com.screensmithy.app/com.screensmithy.app.receiver.KioskDeviceAdminReceiver}
  name=ScreenSmithy
  package=com.screensmithy.app
  isOrganizationOwned=true
```

### Inspect Device Policy State
```bash
adb shell dumpsys device_policy | grep -A 10 "Device Owner:"
```

### Check Lock Task Allowlist
```bash
adb shell dumpsys activity | grep -i "mLockTaskPackages"
```
When ScreenSmithy is active, `com.screensmithy.app` will appear in the allowlist.

### Check Current Lock Task Runtime State
```bash
adb shell dumpsys activity | grep -i "mLockTaskModeState"
```
- `mLockTaskModeState=LOCK_TASK_MODE_LOCKED` (2) indicates active managed kiosk lockdown.
- `mLockTaskModeState=LOCK_TASK_MODE_NONE` (0) indicates standard unlocked mode.

---

## 7. Managed Kiosk Policies Applied

When ScreenSmithy detects that it is the active Device Owner, it can apply the following managed policies via `AndroidDeviceOwnerPolicyManager`:

1. **Lock Task Allowlisting** (`DevicePolicyManager.setLockTaskPackages`):
   Allowlists `com.screensmithy.app` so the application can enter Lock Task mode without prompt or user confirmation.
2. **System UI & Feature Suppression** (`DevicePolicyManager.setLockTaskFeatures`):
   Sets `LOCK_TASK_FEATURE_NONE` (or configurable info features), suppressing system status bar notifications, task switcher, and non-whitelisted system navigation.
3. **Keyguard Suppression** (`DevicePolicyManager.setKeyguardDisabled`):
   Disables the lock screen / keyguard so the TV immediately resumes presentation without manual unlock upon powering on.
4. **Status Bar Suppression** (`DevicePolicyManager.setStatusBarDisabled`):
   Disables notifications, quick settings, and transient pull-down menus.
5. **Persistent Preferred Home Activity** (`DevicePolicyManager.addPersistentPreferredActivity`):
   Designates ScreenSmithy as the permanent default Home application for `Intent.ACTION_MAIN` + `Intent.CATEGORY_HOME`, bypassing the system launcher chooser permanently.

---

## 8. Authenticated Maintenance Path

ScreenSmithy provides a deliberate, authenticated path for administrators to perform maintenance without decommissioning the device or losing Device Owner privileges.

### Exiting Lock Task Mode for Maintenance
1. On the active TV screen, enter the hidden remote sequence:
   $$\text{UP} \to \text{UP} \to \text{DOWN} \to \text{DOWN} \to \text{LEFT} \to \text{RIGHT} \to \text{LEFT} \to \text{RIGHT} \to \text{OK}$$
2. Enter the 6–12 digit Administrator PIN.
3. In the **Admin Dashboard**, locate **Lock Task State**.
4. Click **`[ 🔓 Exit Lock Task Mode (Maintenance) ]`**.
5. ScreenSmithy releases Lock Task Mode (`activity.stopLockTask()`). The device status bar, system settings, and launcher become accessible for maintenance, inspecting network settings, or sideloading updates.
6. Device Owner privileges remain **fully intact**.

### Re-entering Lock Task Mode
- Click **`[ 🔒 Enter Lock Task Mode ]`** in the Admin Dashboard, or simply click **`Resume Kiosk`**.
- ScreenSmithy verifies eligibility and re-enters managed Lock Task Mode (`activity.startLockTask()`).

---

## 9. Decommissioning and Removing Device Owner

To safely decommission a managed TV or return it to standard consumer use:

### Step 9.1: Clear Policies in Admin Dashboard
1. Open Admin Dashboard via hidden remote sequence and PIN.
2. Click **`↩ Clear Persistent Home`** to restore standard TV launcher behavior.

### Step 9.2: Remove Active Administrator via ADB
```bash
adb shell dpm remove-active-admin com.screensmithy.app/com.screensmithy.app.receiver.KioskDeviceAdminReceiver
```

> [!NOTE]
> On production Android builds, the Android OS blocks removing a Device Owner without a full factory reset to protect enterprise integrity. If `remove-active-admin` is disallowed, perform a factory reset:
> ```bash
> adb shell am broadcast -a android.intent.action.MASTER_CLEAR
> ```

---

## 10. Hardware Validation Status

| Target Hardware | Device ID | Android OS | Validation Status | Notes |
|---|---|---|---|---|
| **Google TV Streamer** | `58141HFAG15D0Q` | Android 14 (API 34) | **Standard Mode Verified** | Device currently has an active user account (`sumitatbyf@gmail.com`). In accordance with Android platform security rules and project directives, Device Owner was **not** self-granted to avoid disrupting user accounts. ScreenSmithy verified to gracefully run in Normal Mode, correctly reporting `Device Owner: Inactive` and providing clear on-screen setup guidance. |
| **Clean Emulator / Test TV** | Target Android 14+ | Android 14+ | **Ready for Provisioning** | Execute commands in Section 5 on a freshly wiped emulator or test TV. |

---

## 11. Troubleshooting

| Symptom | Cause | Solution |
|---|---|---|
| `IllegalStateException: Not allowed to set the device owner because there are already some accounts` | A Google account, streaming service account, or email account exists on the device. | Remove all accounts via TV Settings, or execute a factory reset before provisioning. |
| `IllegalStateException: Trying to set device owner but device owner is already set` | Another application is already designated as Device Owner. | Run `adb shell dpm list-owners` to identify the current owner. Decommission the prior owner or factory reset. |
| `SecurityException: Neither user nor current process has android.permission.MANAGE_DEVICE_ADMINS` | `dpm set-device-owner` was executed from an unauthorized context. | Command must be executed via ADB shell (`adb shell dpm ...`). |
| App does not enter Lock Task Mode automatically | App is not yet allowlisted in `DevicePolicyManager.setLockTaskPackages`. | Open Admin Dashboard, verify Device Owner is active, and click `🔒 Allowlist & Lock Task`. |
