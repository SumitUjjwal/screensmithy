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

### Symptom: "You don't have an app that can do this" or Document Picker Failure
- **Cause**: Most Android TV and Google TV devices (including Google TV Streamer, Chromecast with Google TV, Sony Bravia, Xiaomi Mi Box, and TCL Google TV) do not ship with the Android Document Picker (`com.android.documentsui`). Instead, the system registers a stub component (`com.android.tv.frameworkpackagestubs/.Stubs$DocumentsStub`) for `ACTION_OPEN_DOCUMENT`. Invoking this stub does not launch a picker; it displays the system error toast `"You don't have an app that can do this."` and returns `RESULT_CANCELED`.
- **ScreenSmithy Solution**:
  1. ScreenSmithy's `HtmlDocumentPickerContract.isAvailable(context)` actively inspects the intent resolver before launching the intent. If it detects that only framework stubs are present or no resolver exists, it suppresses the unsupported intent and displays a helpful modal dialog:
     - *"No Document Picker App Installed: This Android TV device does not have a system file picker (DocumentsUI) installed. You can transfer HTML files wirelessly from your phone, tablet, or computer over local Wi-Fi."*
  2. Click **"Transfer over Wi-Fi"** directly from that dialog to start a browser-based transfer session.
  3. Alternatively, open Admin Settings $\to$ **Content & Policy** $\to$ select the **"Transfer from Another Device"** tab.

### Symptom: TV shows "No Active Network Connection" and no QR code during Wi-Fi Transfer
- **Cause**: The TV is not connected to a local Wi-Fi or Ethernet network, or has not acquired an IPv4 address via DHCP.
- **Check**: Inspect the diagnostic status box on the TV transfer screen. It will show `"No local IPv4 network interface found (Wi-Fi or Ethernet)"`.
- **Solution**:
  1. Press `Back` to exit the transfer screen.
  2. Open Android TV Settings $\to$ **Network & Internet**.
  3. Ensure Wi-Fi or Ethernet is connected and has obtained an IP address (e.g., `192.168.1.x` or `10.x.x.x`).
  4. Return to ScreenSmithy and reopen **Transfer from Another Device**.

### Symptom: Phone or computer browser cannot connect to TV upload page ("Site cannot be reached" / "Connection timed out")
- **Cause 1 (Different Networks)**: The phone or laptop is on a different Wi-Fi network (or mobile data / cellular 5G) than the TV.
  - **Solution**: Connect both the sending device and the TV to the exact same Wi-Fi SSID.
- **Cause 2 (Client / AP Isolation)**: The Wi-Fi access point or router has **Client Isolation** (also called "AP Isolation", "Guest Network Isolation", or "Station Separation") enabled. This enterprise/guest router feature blocks wireless devices on the same subnet from communicating directly with each other.
  - **Check**: Open a terminal on your computer and run `ping <tv-ip>` or `curl -I http://<tv-ip>:8080/`. If ping times out while the TV shows an active IP address, client isolation is active.
  - **Solution**: Connect both devices to a standard/private Wi-Fi network where local peer-to-peer communication is allowed, or disable AP isolation in the router settings.
- **Cause 3 (Port Omitted or Mismatched)**: The port assigned by ScreenSmithy (e.g., `:8080`) was omitted from the browser address bar.
  - **Solution**: Ensure you include the full URL with port: `http://<tv-ip>:<port>/` (or scan the QR code using your phone camera).

### Symptom: "Invalid pairing code" or "Session has expired"
- **Cause 1 (Typo in Code)**: The 6-digit numeric pairing code displayed on the TV was entered incorrectly in the browser.
  - **Solution**: Verify the 6-digit code on the TV screen. Note that entering 5 incorrect codes consecutively locks out the session for security.
- **Cause 2 (Session Timeout)**: The 5-minute transfer window elapsed.
  - **Solution**: If the TV timer reaches `0:00`, click **Cancel & Close** and click **Transfer from Another Device** to start a fresh 5-minute session.
- **Cause 3 (Session Locked Out)**: Five consecutive failed pairing attempts occurred.
  - **Solution**: The TV transfer screen will display a red security warning: `"Session Locked Out: 5 failed pairing attempts. Transfer terminated."`. Close the dialog and initiate a new session.

### Symptom: "File upload failed" / "Selected file exceeds the maximum allowed size of 25 MB"
- **Cause 1 (File Too Large)**: The uploaded file exceeds the 25 MB limit (`26,214,400 bytes`).
  - **Solution**: Compress inline media (images, audio, fonts) or remove unnecessary embedded binary blobs to bring the file under 25 MB.
- **Cause 2 (Non-HTML File)**: The uploaded file does not end in `.html` or `.htm`, or the content does not start with standard HTML tags (`<!DOCTYPE html` or `<html`).
  - **Solution**: Save the file as a valid `.html` document. ScreenSmithy does not accept `.zip`, `.pdf`, `.png`, or raw script files.

### Symptom: Upload interrupted or stalled halfway through
- **Cause**: Network packet drop, sender device screen turning off, or Wi-Fi disconnection during upload.
- **Solution**:
  - ScreenSmithy streams uploads directly to an isolated temporary file (`scratch/import_staging/wifi_upload_*.tmp`). If the stream is interrupted, the temporary file is deleted automatically and the previous active kiosk configuration remains unaffected.
  - Re-select the file on the sender page and click **"Upload to ScreenSmithy"** again.

### Symptom: Preview renders blank or broken; how to safely roll back
- **Cause**: The uploaded HTML contains syntax errors, references external relative resources (`style.css`, `app.js`) that were not bundled inline, or attempts to execute unsupported APIs.
- **Solution**:
  - While in **Preview Mode**, inspect the content. Notice the top banner: `"👁 PREVIEW MODE — <filename>"`.
  - If the page looks broken, click **"Exit Preview"** on the remote control. ScreenSmithy immediately rolls back and resumes the prior working configuration without interruption.
  - To activate the new content, click **"Confirm & Activate"**. The new file is committed atomically to app-private storage.

### Symptom: "Configured local document is missing from app storage"
- **Cause**: The imported HTML file was deleted or the app storage was cleared.
- **Solution**:
  1. Open Admin Settings $\to$ **Content & Policy**.
  2. Click **Transfer from Another Device** (or **Browse Local Files (SAF)** if a picker is installed).
  3. Re-import your `.html` file.

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

---

## 7. Display Cropping, Overscan & Admin Navigation

### Symptom: Content edges, text, or buttons are cropped near TV screen edges
- **Cause**: Many consumer televisions and commercial displays apply hardware overscan (typically 2% to 5%) to HDMI inputs by default, cutting off content along the screen perimeter.
- **Solution**:
  1. Open the Admin Dashboard (enter remote secret sequence `UP UP DOWN DOWN LEFT RIGHT LEFT RIGHT OK` $\to$ PIN).
  2. Navigate down to Setting 4: **Display Margins (Overscan Protection)**.
  3. Click **Configure Margins**.
  4. Use the on-screen steppers to adjust Top, Bottom, Left, and Right margins independently (typically 3%–5% compensates for standard TV overscan).
  5. The adjustment is previewed live on the TV screen.
  6. Select **✓ Apply & Save** to persist the margin configuration across restarts.
  7. If you ever connect the device to a monitor with 1:1 pixel mapping ("Just Scan" / "Fit to Screen"), click **↺ Reset (0%)** and **✓ Apply & Save**.

### Symptom: Admin Panel opens at the bottom or focuses on Reload Content
- **Cause**: In earlier versions, view initialization placed focus on the "Reload Live Content" button at the bottom of the card, causing the container to scroll down automatically.
- **Solution**: Fixed in current release. Admin sessions now automatically reset scroll offset to `(0, 0)` and assign initial remote focus to the default launcher button at the top of the dashboard.

### Symptom: Native Admin screens or PIN dialog clipped when display margins are configured
- **Cause**: Fixed-width card containers (such as fixed `960dp` on `scrollDashboard`) overflowed the reduced available viewport when non-zero margins and base padding inset the parent root container, shifting content outside screen bounds, or unconstrained vertical stacks overflowed vertically.
- **Solution**: Fixed in current release. All native dialogs and cards use responsive ConstraintLayout bounds (`android:layout_width="0dp"` with `app:layout_constrainedWidth="true"` and `app:layout_constraintWidth_max`), while `AdminPinDialog` incorporates a bounded `NestedScrollView` (`scrollPin`) with compact keypad button proportions (`68dp × 46dp`, `3dp` margins) so that all controls (all numeric keys, Clear, Delete, Cancel, Submit) fit comfortably and remain fully accessible without clipping across all supported display sizes, asymmetric margins, and densities.


