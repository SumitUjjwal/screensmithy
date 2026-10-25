# ScreenSmithy — Security Architecture, Threat Model & Risk Assessment

Security is an essential design foundation for ScreenSmithy. Because unattended kiosk displays run in publicly accessible environments (lobbies, retail floors, conference rooms, operations centers), the application is hardened against physical manipulation, network attacks, untrusted web content, and device-level circumvention.

---

## 1. Core Security Controls Summary

| Security Boundary | Mechanism | Architectural Enforcement |
|---|---|---|
| **Administrative Access** | Hidden Remote Sequence + Numeric PIN | Konami pattern (`UP UP DOWN DOWN LEFT RIGHT LEFT RIGHT OK`) + 6-12 digit PIN. No permanent buttons on presentation screen. |
| **PIN Storage** | Cryptographic Salted Hashing | PBKDF2WithHmacSHA256 with 16-byte secure random salt and 100,000 iterations. Zero plaintext storage. |
| **PIN Brute-Force** | Progressive Lockout | Exponential time delays starting at 5 failed attempts (30s, 60s, 120s...). State persisted in DataStore. |
| **Web Runtime** | Sandboxed Chromium WebView | No native `@JavascriptInterface` bridges. `allowFileAccess = false`, `allowContentAccess = false`. |
| **Local Content** | Sandboxed Asset Loader | Served via `WebViewAssetLoader` over virtual `https://appassets.androidplatform.net/`. No `file://` access. |
| **Transport Security** | Fail-Closed TLS Validation | TLS/SSL errors are unconditionally rejected via `handler.cancel()`. No trust-all or bypass options. |
| **Navigation Security** | Policy Engine Allowlisting | Intercepts `shouldOverrideUrlLoading`. Blocks external schemes (`intent:`, `market:`, `tel:`). Restricts domains. |
| **Log Privacy** | Automated Secret Sanitization | `SecretSanitizer` redacts passwords, tokens, API keys, and sensitive query parameters before writing logs. |
| **Application Backup** | Backup Disabled | `android:allowBackup="false"` in `AndroidManifest.xml` prevents extracting DataStore databases via ADB backup. |
| **Wi-Fi HTML Transfer** | Ephemeral HTTP + Pairing Code | Bound strictly to active local IPv4 interface. 6-digit `SecureRandom` pairing code, constant-time `MessageDigest.isEqual` comparison, 5-attempt rate limit, 5-minute timeout, DNS rebinding Host check, 25 MB limit, low-heap streaming, atomic promotion. |

---

## 2. Comprehensive Threat Analysis & Mitigations

The following matrix documents the 14 threat vectors evaluated in ScreenSmithy, their implementation mitigations, and any residual risk:

### 1. Accidental Admin Access
- **Threat**: Passersby or curious viewers accidentally triggering settings or administrative controls.
- **Mitigation**:
  - The presentation screen has **no visible buttons**, icons, or affordances for admin settings.
  - Administration requires a 9-step directional sequence on the TV remote: `UP` $\to$ `UP` $\to$ `DOWN` $\to$ `DOWN` $\to$ `LEFT` $\to$ `RIGHT` $\to$ `LEFT` $\to$ `RIGHT` $\to$ `OK`.
  - The sequence enforces a 3-second timeout window; random or scattered button presses reset the state machine.
- **Residual Risk**: A user who knows the sequence can summon the PIN prompt, but cannot view or alter settings without the PIN.

### 2. PIN Brute-Force Attacks
- **Threat**: An attacker repeatedly guessing numeric PINs on the TV keypad.
- **Mitigation**:
  - `AdminSessionManager` and `PinSecurity` track consecutive failed attempts.
  - After 5 consecutive failures, a mandatory 30-second lockout is imposed.
  - Subsequent failures escalate lockout duration exponentially (60s, 120s, up to 24 hours).
  - Lockout timestamps and attempt counts are persisted in Jetpack DataStore, preventing attackers from bypassing lockouts by restarting the app or power-cycling the TV.
- **Residual Risk**: Very short PINs (e.g. 6 digits of all the same number) are easier to guess; administrators are instructed during setup to choose strong 6–12 digit PINs.

### 3. Malicious Local HTML / XSS Execution
- **Threat**: An imported local HTML file contains malicious JavaScript attempting to access device hardware, contacts, or internal storage.
- **Mitigation**:
  - `WebViewSecurityConfigurator` disables all Android native JavaScript bridges. No Java/Kotlin object is exposed via `addJavascriptInterface`.
  - Web settings disable geolocation, sensors, and database storage.
  - The local HTML file executes inside the Chromium sandbox with standard web permissions only.
- **Residual Risk**: Untrusted HTML can consume CPU/memory resources (e.g., infinite JavaScript loops), but cannot access Android operating system resources or device data.

### 4. Local File & Path Traversal
- **Threat**: Web content attempting to read sensitive private files (e.g., `/data/data/com.screensmithy.app/databases/` or `/system/etc/`) using `file://` or `../` path traversal.
- **Mitigation**:
  - `WebSettings.setAllowFileAccess(false)`, `setAllowFileAccessFromFileURLs(false)`, and `setAllowUniversalAccessFromFileURLs(false)` are strictly enforced.
  - Local content is served exclusively through `WebViewAssetLoader.InternalStoragePathHandler` mapped to `appassets.androidplatform.net`.
  - `LocalHtmlImporter` validates import paths, rejecting absolute paths and directory traversal sequences (`..`).
- **Residual Risk**: None for the underlying filesystem.

### 5. Unsupported Scheme & External App Launch
- **Threat**: Web content attempting to launch system apps or external tools via custom URI schemes (`intent://`, `market://`, `tel:`, `mailto:`, `settings:`).
- **Mitigation**:
  - `SecureWebViewClient.shouldOverrideUrlLoading` parses every URI scheme.
  - Only `http`, `https`, and the internal asset loader domain are permitted.
  - All external schemes are blocked, intercepted, and logged via `RendererEvent.ExternalAppLaunchBlocked`.
- **Residual Risk**: None within the WebView container.

### 6. Hostile Redirects & Third-Party Navigation
- **Threat**: Configured web content redirecting the display to phishing sites, malware, or inappropriate third-party pages.
- **Mitigation**:
  - The administrator selects an active `NavigationPolicy`:
    - `CONFIGURED_HOST_ONLY`: Strictly locks navigation to the configured root hostname and its subdomains.
    - `ALLOWLIST_ONLY`: Restricts navigation to an explicit allowlist of authorized hostnames.
  - Every redirect or link click is verified against `NavigationPolicyEvaluator`.
  - Unauthorized domain navigation is blocked and emits `RendererEvent.NavigationBlocked`.
- **Residual Risk**: If `ANY_HTTP_HOST` is intentionally selected by the administrator, the WebView can navigate to external sites reachable by links on the page.

### 7. TLS/SSL Validation Failures
- **Threat**: Man-in-the-Middle (MitM) attacks, expired certificates, self-signed rogue certificates, or intercepted public Wi-Fi networks.
- **Mitigation**:
  - In `SecureWebViewClient.onReceivedSslError`, `handler.cancel()` is **unconditionally executed**.
  - ScreenSmithy contains no flags, settings, or debug switches to bypass SSL errors.
  - Emits `RendererState.TlsError`, immediately halting retries and displaying a security warning.
- **Residual Risk**: None. Untrusted connections fail closed.

### 8. Oversized or Corrupt Local Content Import
- **Threat**: Importing a multi-gigabyte file or malformed archive that exhausts device flash memory or crashes the app.
- **Mitigation**:
  - `LocalHtmlImporter` enforces a strict file size ceiling (`MAX_IMPORT_SIZE_BYTES = 50 MB`).
  - Verifies file magic bytes, MIME types, and document encoding before writing to app-private storage.
  - Atomically streams content into temporary staging before committing replacement.
- **Residual Risk**: None. Oversized imports fail with descriptive user errors without affecting active presentation.

### 9. Interrupted or Aborted Content Import
- **Threat**: Device loses power or user unplugs USB drive during an ongoing HTML import, leaving corrupt partial files.
- **Mitigation**:
  - All file imports are staged in `scratch/import_staging/`.
  - The existing presentation configuration is preserved intact until the new document is fully validated and verified.
  - Atomic rename/replace guarantees all-or-nothing configuration updates.
- **Residual Risk**: Temporary partial files in staging are wiped on next import attempt or app initialization.

### 10. Chromium Process / Renderer Crash
- **Threat**: Heavy JavaScript frameworks, WebGL memory leaks, or Chromium engine bugs causing `onRenderProcessGone`.
- **Mitigation**:
  - `KioskWebView.recoverFromRendererCrash` detaches and destroys the dead WebView instance, reclaiming native resources.
  - Recreates a clean `WebView` with centralized security configuration and reloads active content.
  - `StartupRecoveryCoordinator` records crash frequency in a 60-second window. If more than 3 crashes occur within 60s, recovery halts with `PermanentFailure` to prevent battery/CPU crash loops.
- **Residual Risk**: Heavy web apps with unfixable memory leaks will eventually halt at the crash cap rather than looping infinitely.

### 11. No Network Available at Boot
- **Threat**: TV powers on before router or LAN switch has established connectivity, causing an immediate failure.
- **Mitigation**:
  - Initial load failure triggers `BackoffCalculator` with bounded exponential retry (5s, 10s, 20s, 40s, 60s) and random jitter.
  - `ConnectivityManager.NetworkCallback` monitors internet capability. As soon as the network becomes available, an immediate reload is triggered.
  - Content errors display a non-intrusive status banner with the retry countdown and manual retry button.
- **Residual Risk**: If the network never connects, the app displays the connection error screen after exhausting retries.

### 12. Missing Home / Launcher Selection (Normal Mode)
- **Threat**: In Standard Mode, user presses the remote Home button and the device exits ScreenSmithy into the standard TV launcher.
- **Mitigation**:
  - `MainActivity` declares `CATEGORY_HOME` and `CATEGORY_DEFAULT`.
  - In Admin Dashboard, ScreenSmithy provides a direct shortcut to `Settings.ACTION_HOME_SETTINGS` and displays step-by-step guidance.
  - When selected as default Home, pressing Home returns immediately to ScreenSmithy.
- **Residual Risk**: In Standard Mode, non-managed devices allow users to open Settings or switch Home apps. True lockdown requires Device Owner mode.

### 13. Forgotten Administrator PIN
- **Threat**: The device administrator forgets the configured PIN, preventing access to settings.
- **Mitigation**:
  - If ADB is authorized: The administrator can clear application data (`adb shell pm clear com.screensmithy.app`), resetting ScreenSmithy to the first-run setup wizard.
  - If Device Owner is active: Lock Task mode must be exited via ADB or factory reset before clearing data.
- **Residual Risk**: Without ADB access or the PIN, the device must be factory-reset to reconfigure ScreenSmithy. This is an intentional security design to prevent unauthorized tampering.

### 14. OEM-Specific Hardware Keys & Remote Buttons
- **Threat**: TV remotes featuring dedicated branded buttons (Netflix, YouTube, Prime Video) sending hardware-level global intents that bypass normal apps.
- **Mitigation**:
  - **Standard Normal Mode**: Android architecture allows OEM firmware to prioritize dedicated app keys over standard Home apps. ScreenSmithy documents this platform limitation transparently.
  - **Managed Device Owner Mode**: When running in `LOCK_TASK_MODE_LOCKED`, Android suppresses third-party package launches and locks task focus to allowlisted packages.
- **Residual Risk**: On non-compliant or heavily modified consumer TV boxes, OEM kernel firmware may handle specific hardware keys before the Android framework dispatch layer.

### 15. Untrusted Devices on Local Wi-Fi Network / Rogue Ingestion
- **Threat**: An attacker or rogue device on the same local Wi-Fi/LAN attempts to send malicious HTML payloads or deface the kiosk display.
- **Mitigation**:
  - **Ephemeral Server Lifecycle**: The embedded HTTP server is never an always-on background daemon. It binds and listens exclusively when an authenticated administrator opens the `WifiTransferDialog` on the TV.
  - **Cryptographic Pairing Authentication**: The upload endpoint (`/api/upload`) requires a 6-digit numeric pairing code generated via `SecureRandom`.
  - **Non-Automatic Content Activation**: Successfully uploaded files are written to staging only. They are **never activated automatically**. The administrator must review the file metadata, inspect the live rendering in an isolated preview mode on the TV, and press **"Confirm & Activate"** on the remote control.
- **Residual Risk**: Local network traffic is unencrypted HTTP. Anyone with line-of-sight to the TV screen during an active transfer session can see the pairing code.

### 16. DNS Rebinding & Cross-Site Request Forgery (CSRF)
- **Threat**: An attacker hosts a malicious website that forces a user's browser on the same LAN to send unauthorized POST requests to the TV's upload endpoint via DNS rebinding.
- **Mitigation**:
  - **Host Header Validation**: `WifiTransferHttpServer` parses and validates the HTTP `Host` header against the TV's active bound IP and port (`<tv-ip>:<port>`). Requests containing mismatched hostnames (e.g., `attacker.com` or rebinding domains) are rejected immediately with `HTTP 403 Forbidden`.
  - **Strict Method and Path Matching**: Only `POST /api/upload` is accepted for ingestion; all unknown paths or unexpected methods return `HTTP 404` or `405`.
  - **Short Lived Endpoint**: Transfer endpoints are destroyed as soon as the dialog closes or the 5-minute timeout expires.
- **Residual Risk**: None when the client browser adheres to standard HTTP Host header behavior.

### 17. Timing Attacks & Brute-Force Guessing of Pairing Codes
- **Threat**: An attacker on the local network executes an automated dictionary or brute-force script to guess the 6-digit pairing code within the active window.
- **Mitigation**:
  - **Constant-Time Verification**: Verification evaluates pairing codes using `MessageDigest.isEqual()` on UTF-8 byte arrays, preventing timing side-channel attacks.
  - **Rate Limiting & Lockout**: `WifiTransferSession` tracks consecutive failed attempts. After 5 incorrect attempts, the session transitions immediately to `LOCKED_OUT` status. All subsequent requests are rejected with `HTTP 403 Forbidden`, and the TV interface displays a red lockout security banner.
  - **5-Minute Timeout**: The session automatically terminates after 300 seconds, restricting any attack window to at most 5 attempts.
- **Residual Risk**: Negligible ($5 / 1,000,000 = 0.0005\%$ maximum theoretical probability of guessing correctly before lockout).

### 18. Denial of Service via Flooded or Oversized Uploads
- **Threat**: An attacker uploads multi-gigabyte files to exhaust TV RAM, fill internal flash storage, or crash the application.
- **Mitigation**:
  - **Fail-Fast Size Check**: `WifiTransferHttpServer` inspects `Content-Length` headers. If the payload exceeds 25 MB (`26,214,400 bytes`), the request is rejected with `HTTP 413 Payload Too Large` without reading the body.
  - **Low-Heap Streaming Parser**: `MultipartStreamHandler` streams data directly from the socket stream to a temporary disk file (`scratch/import_staging/wifi_upload_*.tmp`) using an 8 KB buffer, preventing heap memory exhaustion.
  - **Dynamic Stream Enforcement**: Even if `Content-Length` is omitted, the stream parser terminates ingestion and deletes the staging file if byte count exceeds 25 MB.
  - **Staging Cleanup**: Incomplete, aborted, or rejected staging files are wiped immediately.
- **Residual Risk**: None to application heap or system stability.

---

## 3. Wi-Fi HTML Transfer Security Architecture & Threat Model

ScreenSmithy provides local Wi-Fi file transfer to resolve platform limitations on Android TV and Google TV devices lacking a functional `DocumentsUI` file picker. The subsystem is engineered under the assumption that the local network may contain untrusted devices.

```
┌────────────────────────────────────────────────────────────────────────────────┐
│                           LOCAL NETWORK SECURITY MODEL                          │
│                                                                                │
│   ┌───────────────────┐               HTTP GET / (Web UI)                      │
│   │   Sender Device   │ ───────────────────────────────────────────────┐       │
│   │  (Browser Client) │                                                │       │
│   └─────────┬─────────┘               HTTP POST /api/upload            │       │
│             │            (Host Header Check + 6-digit Pairing Code)    │       │
│             │                                                          ▼       │
│             │                                            ┌───────────────────┐ │
│             │                                            │ ScreenSmithy TV   │ │
│             │                                            │ (Port 8080..8100) │ │
│             │                                            └─────────┬─────────┘ │
│             │                                                      │           │
│             │ [Local Network Isolation / Client Isolation]         ▼           │
│             │ Must share same IPv4 subnet without AP isolation     │           │
│             │                                                      ▼           │
│   ┌─────────▼─────────┐                                  ┌───────────────────┐ │
│   │ Attacker / Untrusted ─── Rebind / Brute Force (5x) ─►│ 403 Forbidden /   │ │
│   │ Device on LAN     │                                  │ Session Locked    │ │
│   └───────────────────┘                                  └───────────────────┘ │
└────────────────────────────────────────────────────────────────────────────────┘
```

### 3.1 Local Network Threat Model
- **Network Boundaries**: ScreenSmithy assumes any device on the same local Wi-Fi or Ethernet network can discover open ports. A local IP address is never considered an authenticated entity.
- **Transport Security**: Traffic over the local Wi-Fi network uses **unencrypted HTTP**. ScreenSmithy explicitly documents that local Wi-Fi transfer is designed for trusted, private management networks (e.g. corporate or home Wi-Fi). It does **not** employ self-signed HTTPS certificates on the TV, which would cause untrusted certificate warnings in sender browsers and encourage users to bypass TLS warnings.
- **Independence from Internet TLS**: The local HTTP transfer server is completely decoupled from the WebView renderer. ScreenSmithy's **fail-closed TLS policy** for remote web kiosk URLs remains strictly enforced and is never weakened or bypassed.

### 3.2 Network Interface Binding & Safe Port Allocation
- **Interface Selection**: `NetworkUtils.findLocalIpv4Address()` enumerates active network interfaces (`wlan*` or `eth*`), strictly selecting local site/link IPv4 addresses. It explicitly avoids binding indiscriminately to `0.0.0.0` (all interfaces) or cellular interfaces.
- **Safe Port Range**: Scans ports dynamically between `8080` and `8100`. It checks port availability with `SO_REUSEADDR` to avoid `BindException` collisions resulting from sockets in `TIME_WAIT` state.
- **Zero Background Exposure**: The server runs exclusively within a coroutine supervisor bound to the lifecycle of the on-screen dialog. When the administrator cancels, completes the transfer, or exits the app, the server socket is immediately closed.

### 3.3 Cryptographic Pairing & Authentication
- **Secure Random Secret**: Upon session initialization, `WifiTransferSession` generates a 6-digit numeric pairing code using `java.security.SecureRandom`.
- **Constant-Time Verification**: Verification uses `MessageDigest.isEqual()` on the binary UTF-8 representation of the pairing codes to prevent timing analysis.
- **No Credentials in QR Code**: The displayed QR code encodes exclusively the URL (`http://<ip>:<port>/`). The pairing code is displayed visually on the TV screen and must be manually entered by the administrator into the web interface, ensuring physical line-of-sight presence.

### 3.4 Rate Limiting & Session Expiration
- **Rate-Limiting Lockout**: Any attempt to submit an invalid pairing code increments a failed attempt counter. Upon the **5th consecutive failure**, the session transitions immediately to `SessionStatus.LOCKED_OUT`.
- **Permanent Lockout for Session**: In `LOCKED_OUT` state, the server rejects all subsequent incoming requests with `HTTP 403 Forbidden` and terminates the session.
- **5-Minute Countdown**: The session has an absolute maximum lifetime of 300 seconds (5 minutes). If no valid upload occurs within this window, the session expires and the server is closed.

### 3.5 DNS Rebinding & Cross-Origin Protections
- **Host Header Enforcement**: `WifiTransferHttpServer` parses the incoming `Host` header and compares it against `<ip>:<port>` and `<ip>`. Requests bearing third-party hostnames or DNS rebinding domains are rejected with `HTTP 403 Forbidden`.
- **Payload Verification**: Only `multipart/form-data` uploads containing the expected form fields (`pairingCode` and `file`) are processed.

### 3.6 Memory Bounds & Streaming Ingestion
- **25 MB Hard Limit**: Upload size is capped at 25 MB (`MAX_UPLOAD_SIZE_BYTES = 25 * 1024 * 1024`). `Content-Length` headers exceeding this threshold are rejected upfront with `HTTP 413 Payload Too Large`.
- **Low-Heap Streaming**: `MultipartStreamHandler` parses the multipart boundary and streams incoming file bytes directly to a private temporary file on disk using a fixed 8 KB buffer. File data is never buffered in JVM heap memory.
- **Disk Exhaustion Guard**: Staging disk space is verified before committing the file.

### 3.7 Content Validation & Atomic App-Private Storage
- **Filename Sanitization**: Uploaded filenames are stripped of all directory path segments (`/`, `\`, `..`), sanitized to safe characters (`[a-zA-Z0-9._-]`), and checked for `.html` or `.htm` extensions. Empty or invalid names default safely to `index.html`.
- **HTML Content Validation**: The initial 1 KB of the staged file is checked for standard HTML markers (`<!DOCTYPE html` or `<html`). Non-HTML files are rejected and deleted.
- **Atomic Promotion**: The staged file is moved to its final path (`context.filesDir/imported_html/<documentId>/<sanitizedFileName>.html`) using POSIX atomic rename (`File.renameTo`), ensuring no partial files exist in the content directory.
- **Private Storage**: Stored content resides in ScreenSmithy's app-private directory (`context.filesDir`), inaccessible to other apps and without requiring broad Android storage permissions.

### 3.8 Preview Isolation & Manual Activation
- **Zero Automatic Activation**: Uploaded content is never loaded directly into the production kiosk presentation.
- **Isolated Preview**: The administrator inspects the uploaded file in preview mode, where it is rendered via AndroidX `WebViewAssetLoader` with all native JavaScript interfaces and file access disabled.
- **Rollback Safety**: If the administrator cancels preview or if rendering fails, ScreenSmithy rolls back to the previous working configuration without downtime.

---

## 4. Data Privacy & Log Redaction

ScreenSmithy records runtime diagnostics locally in a bounded in-memory circular buffer (`BoundedDiagnosticLogger`).

- **Secret Sanitization**: All log entries pass through `SecretSanitizer.sanitizeMessage()` and `SecretSanitizer.sanitizeUrl()`.
- **Patterns Redacted**:
  - Sensitive query parameters: `token`, `key`, `api_key`, `secret`, `password`, `auth`, `access_token`, `session`, `jwt`.
  - Credentials in URLs: `https://user:pass@example.com` $\to$ `https://***:***@example.com`.
  - Authentication sequences, raw PIN digits, and Wi-Fi pairing codes are never logged in plaintext.
- **Storage**: Logs exist in memory and are only written to device storage when the administrator explicitly clicks **"Export Diagnostics"** in the PIN-protected Admin Dashboard.

---

## 5. Residual Risks & Production Recommendations

1. **Physical Port Tampering**:
   - In unattended public installations, disable USB debugging (`adb`) in TV Developer Options after deployment, or physically block external USB ports to prevent unauthorized ADB access.
2. **Network Isolation**:
   - For digital signage displays, isolate kiosks onto a dedicated VLAN or guest network with outbound HTTPS egress only.
   - When using Wi-Fi transfer, connect the TV and sending device to an administrative Wi-Fi network that permits local peer-to-peer communication (i.e. where wireless client isolation / AP isolation is disabled).
3. **Firmware Updates**:
   - Maintain modern Android security patch levels on the TV display to ensure the underlying Chromium WebView engine (`com.google.android.webview`) receives upstream browser security patches.

