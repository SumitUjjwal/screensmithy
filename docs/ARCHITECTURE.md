# ScreenSmithy — System Architecture & Design Specification

ScreenSmithy is an enterprise-grade Android kiosk application engineered specifically for Android TV, Google TV, and dedicated Android displays. It transforms standard hardware into a resilient digital signage and interactive display kiosk supporting locally imported HTML5 documents and remote HTTP/HTTPS web applications.

---

## 1. Architectural Philosophy & Principles

The architecture adheres to Clean Architecture and Domain-Driven Design (DDD) principles:

- **Separation of Concerns**: Core domain models and business rules are completely independent of the Android framework, UI widgets, and persistence mechanisms.
- **Fail-Closed Security**: Under no circumstances does the application bypass TLS certificate errors, execute unvalidated schemes, or permit path traversal into app-private storage.
- **Idempotent Lifecycle**: TV devices experience frequent input switching, screen power toggles, and remote interactions that fire `onResume`, `onNewIntent`, and `onWindowFocusChanged`. The kiosk engine drops redundant reloads to ensure flicker-free presentation.
- **Resilient Recovery**: Network drops, DNS timeouts, and Chromium renderer terminations are handled automatically with bounded exponential backoff and jitter, strictly preventing busy-loops or system exhaustion.
- **Transparent Operational Modes**: ScreenSmithy clearly differentiates between **Standard Normal Mode** (accessible without special privileges) and **Managed Device Owner Mode** (hardware-enforced Lock Task Mode), accurately reporting actual system capabilities without false affordances.

---

## 2. Layered Package Structure & Responsibilities

```
com.screensmithy.app/
├── domain/                         # Pure business logic and interfaces
│   ├── model/                     # Core immutable domain entities
│   │   ├── ContentSource.kt       # RemoteUrl, LocalHtml, or None
│   │   ├── NavigationPolicy.kt    # NavigationMode, allowedHosts, externalAppPolicy
│   │   ├── RetryPolicy.kt         # maxRetries, initialBackoff, maxBackoff
│   │   ├── DisplayPreferences.kt  # keepScreenAwake, immersiveMode, reloadOnNetwork
│   │   ├── KioskConfiguration.kt  # Root persisted configuration aggregate
│   │   ├── LocalHtmlMetadata.kt   # Metadata for imported offline HTML documents
│   │   └── RuntimeStatus.kt       # Transient load diagnostics and timestamps
│   ├── state/                     # Initial evaluation and startup state machine
│   │   ├── StartupDecisionEngine.kt
│   │   └── StartupState.kt        # Initializing, SetupRequired, ReadyToPresent, Error
│   ├── recovery/                  # Idempotent loading, retry backoff, and crash handling
│   │   ├── StartupRecoveryCoordinator.kt
│   │   ├── ErrorClassifier.kt     # Classifies transient vs fatal/non-retryable errors
│   │   ├── ErrorClassification.kt
│   │   └── BackoffCalculator.kt   # Exponential backoff with +/- 15% random jitter
│   ├── url/                       # Strict URL and navigation policy validation
│   │   ├── UrlValidator.kt
│   │   ├── UrlValidationResult.kt
│   │   └── NavigationPolicyEvaluator.kt
│   ├── importer/                  # Safe local HTML archive and document importation
│   │   ├── LocalHtmlImporter.kt
│   │   ├── ContentStreamResolver.kt
│   │   └── ImportResult.kt
│   ├── auth/                      # Hidden remote sequence and PIN security
│   │   ├── RemoteSequenceDetector.kt
│   │   ├── AdminAuthRepository.kt
│   │   ├── AdminSessionManager.kt
│   │   └── PinSecurity.kt         # PBKDF2WithHmacSHA256, salting, rate limiting
│   ├── devicepolicy/              # Device Owner and Lock Task mode abstractions
│   │   ├── DeviceOwnerPolicyManager.kt
│   │   ├── LockTaskStatus.kt
│   │   └── PolicyExecutionResult.kt
│   ├── diagnostics/               # Diagnostic logging and JSON export
│   │   ├── DiagnosticLogger.kt
│   │   ├── DiagnosticReportExporter.kt
│   │   └── SecretSanitizer.kt     # Redacts query tokens, passwords, and secrets
│   └── wizard/                    # Multi-step setup wizard coordination
│       ├── SetupWizardManager.kt
│       └── SetupWizardSnapshot.kt
├── data/                          # Android framework implementations of domain interfaces
│   ├── repository/
│   │   └── DataStoreConfigurationRepository.kt  # Jetpack DataStore (Proto-style JSON)
│   ├── auth/
│   │   └── DataStoreAdminAuthRepository.kt       # Secure salted PIN hash storage
│   ├── devicepolicy/
│   │   └── AndroidDeviceOwnerPolicyManager.kt    # Real DevicePolicyManager integration
│   ├── importer/
│   │   └── AndroidContentStreamResolver.kt       # Android ContentResolver bridge
│   └── diagnostics/
│       └── AndroidSystemDiagnosticInspector.kt   # Hardware, OS, and WebView versions
├── ui/                            # Presentation layer (TV remote & D-pad optimized)
│   ├── MainActivity.kt            # Central kiosk host activity
│   ├── renderer/                  # Centralized WebView content presentation
│   │   ├── KioskWebView.kt        # Custom container with teardown/recreate lifecycle
│   │   ├── SecureWebViewClient.kt # Policy enforcement, TLS fail-closed, asset loading
│   │   ├── SecureWebChromeClient.kt # Console logging and title observation
│   │   └── WebViewSecurityConfigurator.kt # Hardened WebSettings
│   ├── auth/
│   │   └── AdminPinDialog.kt      # On-screen PIN entry dialog with D-pad keypad
│   ├── admin/
│   │   ├── AdminDashboardDialog.kt # Settings, Lock Task toggles, diagnostics export
│   │   └── AdminContentDialog.kt   # Live content staging, testing, and preview
│   └── importer/
│       └── HtmlDocumentPickerContract.kt # Storage Access Framework (SAF) contract
└── receiver/                      # Broadcast receivers
    ├── KioskDeviceAdminReceiver.kt # Device Admin receiver for Device Owner mode
    └── BootCompletedReceiver.kt   # Boot completion handler for Device Owner autostart
```

---

## 3. System Lifecycle & Startup Flow

The following Mermaid sequence diagram illustrates the lifecycle from initial boot to live presentation, error backoff, and administrative intervention:

```mermaid
sequenceDiagram
    autonumber
    actor TV as Android TV System
    participant Main as MainActivity
    participant DS as ConfigurationRepository
    participant Engine as StartupDecisionEngine
    participant Coord as StartupRecoveryCoordinator
    participant WV as KioskWebView
    participant Pin as AdminPinDialog

    TV->>Main: onCreate() / onResume()
    Main->>DS: getConfiguration()
    DS-->>Main: KioskConfiguration
    Main->>Engine: evaluate(config)
    
    alt Setup Required (!isSetupCompleted)
        Engine-->>Main: StartupState.SetupRequired
        Main->>Main: Display SetupWizardUI
    else Configuration Corrupted / Missing
        Engine-->>Main: StartupState.RecoverableConfigurationError
        Main->>Main: Display ErrorCard with Admin Access
    else Valid Configuration (ReadyToPresent)
        Engine-->>Main: StartupState.ReadyToPresent
        Main->>Coord: evaluateLoad(source, policy)
        alt Duplicate Load Request (Idempotent Check)
            Coord-->>Main: RecoveryAction.SkipDuplicateLoad
            Note over Main: Discard redundant reload; keep presenting
        else Proceed With Load
            Coord-->>Main: RecoveryAction.ProceedWithLoad
            Main->>WV: loadContent(source, policy)
            
            alt Network / HTTP Failure (Transient)
                WV-->>Main: RendererState.MainFrameError / HttpError(5xx)
                Main->>Coord: onLoadError(state, retryPolicy)
                Coord-->>Main: RecoveryAction.ScheduleRetry(delayMs, attempt)
                Main->>Main: Show ErrorCard + Countdown Banner
                Note over Main: Wait delayMs (5s..60s + jitter)
                Main->>WV: loadContent(source, policy)
            else TLS / SSL Failure (Fatal)
                WV-->>Main: RendererState.TlsError
                Main->>Coord: onLoadError(state, retryPolicy)
                Coord-->>Main: RecoveryAction.PermanentFailure(TLS_FAILURE)
                Main->>Main: Show Fail-Closed ErrorCard (No Auto-Retry)
            else Content Loads Successfully
                WV-->>Main: RendererState.Ready
                Main->>Coord: onLoadSuccess(url)
                Main->>Main: Hide ErrorCard; Present Fullscreen
            end
        end
    end

    opt Hidden Remote Admin Sequence
        TV->>Main: dispatchKeyEvent (UP UP DOWN DOWN LEFT RIGHT LEFT RIGHT OK)
        Main->>Pin: show() (Admin Authentication)
        Pin-->>Main: onAuthenticated
        Main->>Main: openAdminDashboardDialog()
    end
```

---

## 4. WebView Security Boundaries & Content Rendering

ScreenSmithy renders web and local content inside a strictly sandboxed Chromium instance:

### Centralized Security Configuration (`WebViewSecurityConfigurator`)
- **JavaScript**: Explicitly enabled to support modern web applications and dashboards.
- **JavaScript Interfaces**: **None registered**. No Android native objects or reflection bridges are ever exposed to webpage JavaScript (`@JavascriptInterface` is strictly disallowed).
- **Filesystem Access**: `allowFileAccess = false`, `allowFileAccessFromFileURLs = false`, `allowUniversalAccessFromFileURLs = false`, `allowContentAccess = false`. The WebView cannot read arbitrary device storage or private internal files directly via `file://`.
- **Geolocation & Sensors**: `setGeolocationEnabled(false)`.
- **Mixed Content**: `MIXED_CONTENT_NEVER_ALLOW`. Insecure HTTP resources inside HTTPS pages are blocked.
- **Safe Browsing**: Enabled on supported Android platforms.

### Local HTML Sandboxing (`WebViewAssetLoader`)
Local HTML documents imported into app-private storage (`context.filesDir/imported_html/`) are served exclusively through `androidx.webkit.WebViewAssetLoader`:
- Virtual URL: `https://appassets.androidplatform.net/imported_content/<subpath>`
- Assets run under a secure `https://` origin rather than `file://`, granting standard web capability support (e.g., SVG, Canvas, modern JS modules) while strictly prohibiting path traversal beyond the designated import directory.

### Navigation Policy Enforcement (`SecureWebViewClient`)
Every navigation attempt is intercepted in `shouldOverrideUrlLoading`:
- **Allowed Schemes**: Only `http`, `https`, and the internal asset loader domain are permitted.
- **Dangerous Schemes**: `intent://`, `file://`, `content://`, `javascript:`, `market:`, `tel:`, and `mailto:` are intercepted, blocked, and logged.
- **Navigation Modes**:
  1. `CONFIGURED_HOST_ONLY`: Navigation is strictly locked to the configured root host (and its subdomains).
  2. `ALLOWLIST_ONLY`: Navigation is limited to the configured host plus an explicit list of trusted hostnames.
  3. `ANY_HTTP_HOST`: Permitted across arbitrary external HTTP/HTTPS domains.
- Blocked navigations emit `RendererEvent.NavigationBlocked` and prevent the WebView from loading unauthorized third-party destinations.

### TLS Fail-Closed Mandate
In `onReceivedSslError`:
- `handler.cancel()` is **unconditionally executed**.
- Certificate errors are never bypassed, ignored, or made optional.
- The failure emits `RendererState.TlsError`, immediately halting retries and displaying a security warning banner.

---

## 5. Startup Recovery & Crash Resilience Engine

Unattended kiosk deployments require robust recovery from unexpected platform faults:

1. **Idempotency Guard**:
   `StartupRecoveryCoordinator.evaluateLoad` tracks `activeSource` and `activePolicy`. If an `onResume` or `onNewIntent` event occurs while the requested content is already presenting or actively loading, the coordinator issues `RecoveryAction.SkipDuplicateLoad`.

2. **Bounded Exponential Backoff with Jitter**:
   $$\text{Delay} = \min\left(\text{initialBackoff} \times 2^{\text{attempt} - 1},\, \text{maxBackoff}\right) \times \text{jitter}(0.85 \dots 1.15)$$
   - Default: Starts at 5s, doubles to 10s, 20s, 40s, capped at 60s.
   - Jitter prevents thundering herd synchronicity when LAN switches or Wi-Fi routers reboot.
   - Capped by `maxRetries` (default: 3). Upon exhaustion, enters a permanent error state with manual retry and admin options.

3. **Chromium Renderer Termination Recovery**:
   When Chromium crashes or the OS kills the isolated rendering process (`onRenderProcessGone`):
   - The dead `WebView` view is detached from the layout hierarchy and `destroy()` is called to reclaim native graphics memory.
   - A fresh `WebView` is initialized with security settings.
   - Crash loop protection: `StartupRecoveryCoordinator` records crash timestamps in a sliding 60-second window. If more than 3 crashes occur within 60s, automatic recovery is halted to prevent continuous crash looping.

4. **Network Restoration Recovery**:
   `ConnectivityManager.NetworkCallback` monitors network availability. If the kiosk is in an error or retry state when the network returns, an immediate reload is triggered. If the kiosk is already presenting content cleanly, network reconnection events do not interrupt the active display.

---

## 6. Authentication & Administration Security Architecture

Administrative operations are protected by a two-tier hidden access control model:

1. **Hidden Remote-Control Sequence**:
   - Sequence: `UP` $\to$ `UP` $\to$ `DOWN` $\to$ `DOWN` $\to$ `LEFT` $\to$ `RIGHT` $\to$ `LEFT` $\to$ `RIGHT` $\to$ `OK` (Konami pattern).
   - Tracked in `MainActivity.dispatchKeyEvent` via `RemoteSequenceDetector`.
   - Intermediate directional keys return `false` so normal web page navigation and scrolling continue unaffected.
   - Timeout: Sequence resets automatically after 3 seconds of inactivity.
   - Successful sequence consumes the final `OK` event and launches the PIN verification gate.

2. **Cryptographic PIN Storage & Verification**:
   - PIN requirements: 6 to 12 numeric digits.
   - Algorithm: **PBKDF2 with HMAC-SHA256** (`PBKDF2WithHmacSHA256`).
   - Salting: Cryptographically secure 16-byte random salt (`java.security.SecureRandom`).
   - Iterations: 100,000 rounds.
   - Memory Safety: PIN characters are processed as `CharArray`, and buffers are zeroed out (`enteredPin.fill('\u0000')`) immediately after hash calculation.

3. **Brute-Force Rate Limiting & Lockout**:
   - Maximum consecutive failed attempts: **5 attempts**.
   - Progressive Lockout:
     - 5 failed attempts: 30-second lockout.
     - 6 failed attempts: 60-second lockout.
     - 7 failed attempts: 120-second lockout.
     - 8+ failed attempts: Exponentially doubled up to 24 hours.
   - Lockout timestamps are persisted in DataStore to prevent circumvention via app restart.

---

## 7. Operating Mode Architecture: Standard vs Managed

ScreenSmithy runs on any Android TV device, adapting its capabilities based on provisioning:

| Dimension | Standard Normal Mode | Managed Device Owner Mode |
|---|---|---|
| **Provisioning** | Standard APK installation. No factory reset. | Provisioned via ADB or QR code on unprovisioned device. |
| **Launcher Integration** | Registered for `CATEGORY_HOME` + `CATEGORY_DEFAULT`. User selects ScreenSmithy in TV Home settings. | `DevicePolicyManager.addPersistentPreferredActivity` sets ScreenSmithy as permanent Home without user prompts. |
| **Lock Task Mode** | Not available (system screen pinning only). | Full `LOCK_TASK_MODE_LOCKED` allowlisted in policy. |
| **System Key Suppression** | Standard TV OS handles Home/Power/OEM buttons. | Home and Back buttons locked to kiosk; status bar and notifications suppressed. |
| **Maintenance Path** | Exit kiosk via Admin Dashboard or TV Settings. | Authenticated `stopLockTask()` release in Admin Dashboard. |
