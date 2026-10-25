# ScreenSmithy — Troubleshooting & Diagnostic Guide

This guide provides operational solutions for common setup issues, network errors, display behaviors, and TV platform limitations encountered when deploying ScreenSmithy.

---

## Quick Diagnostic Checklist

When troubleshooting unexpected behavior on an Android TV device, check these core indicators:

1. **Verify Operating Mode**: Open Admin Dashboard (`UP UP DOWN DOWN LEFT RIGHT LEFT RIGHT OK` $\to$ PIN). Check **Operating Mode**:
   - `Standard Normal Mode`: Running as ordinary APK.
   - `Managed Device Owner Mode`: Running with hardware enterprise policies.
2. **Inspect System Diagnostics**: Check OS build, WebView engine version, and network state in the dashboard.
3. **Export Diagnostic Report**: In the Admin Dashboard, click **Export Diagnostics** to save a redacted JSON report for review.

---

## 1. Setup & Home Launcher Issues

### Symptom: Pressing the remote "Home" button exits ScreenSmithy to the TV home screen
- **Cause**: ScreenSmithy has not been designated as the default Home app.
- **Solution (Standard Normal Mode)**:
  1. Open Admin Dashboard $\to$ click **Home Settings**.
  2. In Android TV Settings, locate **Default Apps > Home App** (or **Home Screen**).
  3. Select **ScreenSmithy** as the default Home app.
  4. Now, pressing Home will return immediately to ScreenSmithy.
- **Solution (Managed Device Owner Mode)**:
  1. Open Admin Dashboard.
  2. Click **Set Persistent Home**. ScreenSmithy will permanently register itself as the default launcher without system prompts.

### Symptom: Device powers on to the standard Android TV launcher instead of ScreenSmithy
- **Cause**: Android 10+ restricts background broadcast receivers from launching activities. In Standard Mode, the TV system uses its registered Home launcher on boot.
- **Solution**: Follow the steps above to set ScreenSmithy as the default Home app. On TV restart, the system will naturally boot directly into ScreenSmithy.

---

## 2. Device Owner & Lock Task Mode Issues

### Symptom: `dpm set-device-owner` fails with `IllegalStateException: Not allowed to set device owner because there are already some accounts`
- **Cause**: Android security prevents granting Device Owner if any Google, streaming service, or user accounts are configured on the device.
- **Solution**:
  1. Check existing accounts via ADB:
     ```bash
     adb shell dumpsys account | grep -E "Account \{|User 0:"
     ```
  2. Remove all accounts via `Settings > Accounts & Sign-in`.
  3. If accounts cannot be removed (standard on Google TV), perform a factory reset:
     ```bash
     adb shell am broadcast -a android.intent.action.MASTER_CLEAR
     ```
  4. Complete initial TV setup **without adding a Google account**, then run `dpm set-device-owner`. See [`DEVICE_OWNER_SETUP.md`](DEVICE_OWNER_SETUP.md) for full instructions.

### Symptom: Lock Task Mode does not engage automatically
- **Cause**: The app is running in Standard Mode, or has not been allowlisted in Device Owner policy.
- **Solution**: Open Admin Dashboard. Verify Device Owner status. Click **Allowlist & Lock Task**.

---

## 3. Remote Secret Sequence & PIN Lockout

### Symptom: Entering `UP UP DOWN DOWN LEFT RIGHT LEFT RIGHT OK` does not open the PIN dialog
- **Checks**:
  - The sequence must be entered steadily within **3 seconds**. Long pauses between button presses reset the detector.
  - Directional keys on the D-pad must be used (not channel up/down or volume keys).
  - Verify that the active screen is receiving key focus. If an external dialog is open, press `Back` once to return focus to the presentation window.
- **Alternative**: If the display is showing an Error Card, click the visible **"Open Admin Settings (PIN Required)"** button.

### Symptom: "Authentication locked out. Try again in X seconds"
- **Cause**: 5 or more incorrect PINs were entered consecutively.
- **Solution**:
  - Wait for the countdown timer to expire. Lockout duration increases exponentially with repeated failed attempts (30s, 60s, 120s...).
  - If the PIN is forgotten and you have ADB access, clear app data to reset the wizard:
    ```bash
    adb shell pm clear com.screensmithy.app
    ```

---

## 4. Local HTML & Content Import Issues

### Symptom: "Configured local document is missing from app storage"
- **Cause**: The imported HTML file was deleted or the app storage was cleared.
- **Solution**:
  1. Open Admin Settings $\to$ **Content & Policy**.
  2. Click **Import Local HTML**.
  3. Re-import your `.html` file from internal storage or a connected USB drive.

### Symptom: Local HTML displays text but images, styles, or scripts are missing
- **Cause**: A single `.html` file was imported without its referenced external CSS, JS, or image assets.
- **Solution**:
  - ScreenSmithy serves local content through `WebViewAssetLoader` mapped to `context.filesDir/imported_html/`.
  - For single-file HTML deployments, bundle styles and scripts inline:
    - Use inline `<style>` tags rather than `<link rel="stylesheet">`.
    - Use inline `<script>` tags.
    - Embed small images as Base64 data URIs (`data:image/png;base64,...`).

---

## 5. Network, HTTPS & Blank Screen Issues

### Symptom: "Security verification failed (TLS/SSL certificate error)"
- **Cause**: The configured HTTPS URL has an invalid, expired, self-signed, or untrusted SSL certificate.
- **Solution**:
  - ScreenSmithy enforces a **strict fail-closed TLS policy** to protect against MitM attacks. Certificate errors are never bypassed.
  - Install a valid SSL certificate (e.g., Let's Encrypt) on the destination web server.
  - Ensure the TV device date and time are accurate (`Settings > System > Date & Time`). An incorrect system clock causes valid certificates to fail verification.

### Symptom: Page loads blank white or black without error
- **Checks**:
  - Check whether the webpage requires login or user interaction.
  - Open Admin Dashboard and inspect the **Runtime Diagnostics Log** for blocked navigation or console errors.
  - If the page uses client-side routing (Single Page Apps like React, Vue, Angular), ensure the server correctly routes all paths to `index.html`.

### Symptom: "Retrying in X seconds (attempt 1 of 3)..."
- **Cause**: Transient network error (DNS failure `ERR_NAME_NOT_RESOLVED`, server timeout, HTTP 500/502/503).
- **Solution**:
  - ScreenSmithy will automatically retry using exponential backoff (5s, 10s, 20s, 40s, 60s).
  - When network connectivity returns, ScreenSmithy detects the change and reloads immediately.
  - To trigger an immediate manual reload, click **"↻ Retry Loading"**.

---

## 6. Display Sleep & OEM Hardware Issues

### Symptom: TV screen dims or enters sleep/screensaver mode while kiosk is running
- **Solution**:
  1. Open Admin Dashboard.
  2. Verify that **Keep Screen Awake (`FLAG_KEEP_SCREEN_ON`)** is toggled **[ ON ]**.
  3. In TV System Settings (`Settings > System > Power & Energy`), set **Turn off display** / **Sleep timer** to **Never**.
  4. In Ambient Mode settings, disable the TV screensaver / backdrop.

### Symptom: Remote buttons for Netflix, YouTube, or Prime Video exit the kiosk
- **Cause (Standard Mode)**: Consumer TV firmware maps branded remote buttons to system-level global intent shortcuts that bypass standard user applications.
- **Solution**:
  - In **Managed Device Owner Mode**, Lock Task Mode (`LOCK_TASK_MODE_LOCKED`) suppresses unauthorized third-party package launches.
  - For commercial deployments, consider using dedicated enterprise hospitality displays, or commercial Android TV remotes without third-party streaming shortcut keys.
