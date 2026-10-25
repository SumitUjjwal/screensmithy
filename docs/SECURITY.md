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

---

## 3. Data Privacy & Log Redaction

ScreenSmithy records runtime diagnostics locally in a bounded in-memory circular buffer (`BoundedDiagnosticLogger`).

- **Secret Sanitization**: All log entries pass through `SecretSanitizer.sanitizeMessage()` and `SecretSanitizer.sanitizeUrl()`.
- **Patterns Redacted**:
  - Sensitive query parameters: `token`, `key`, `api_key`, `secret`, `password`, `auth`, `access_token`, `session`, `jwt`.
  - Credentials in URLs: `https://user:pass@example.com` $\to$ `https://***:***@example.com`.
  - Authentication sequences and raw PIN digits are never logged.
- **Storage**: Logs exist in memory and are only written to device storage when the administrator explicitly clicks **"Export Diagnostics"** in the PIN-protected Admin Dashboard.

---

## 4. Residual Risks & Production Recommendations

1. **Physical Port Tampering**:
   - In unattended public installations, disable USB debugging (`adb`) in TV Developer Options after deployment, or physically block external USB ports to prevent unauthorized ADB access.
2. **Network Isolation**:
   - For digital signage displays, isolate kiosks onto a dedicated VLAN or guest network with outbound HTTPS egress only.
3. **Firmware Updates**:
   - Maintain modern Android security patch levels on the TV display to ensure the underlying Chromium WebView engine (`com.google.android.webview`) receives upstream browser security patches.
