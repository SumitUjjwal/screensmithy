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
│   ├── transfer/                  # Local Wi-Fi transfer session lifecycle and state
│   │   ├── WifiTransferSession.kt # Session state, 6-digit PIN, 5-min timeout, rate limiting
│   │   └── WifiTransferManager.kt # Session coordinator and countdown ticker
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
│   ├── transfer/                  # Local HTTP upload service and network resolution
│   │   ├── NetworkUtils.kt        # Local IPv4 resolution and safe port search (SO_REUSEADDR)
│   │   ├── WifiTransferHttpServer.kt # ServerSocket HTTP server, DNS rebinding guard, constant-time auth
│   │   ├── MultipartStreamHandler.kt # Memory-efficient streaming multipart parser
│   │   └── SenderHtmlTemplate.kt  # Embedded responsive dark-mode browser upload page
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
│   ├── transfer/                  # TV-side Wi-Fi transfer presentation
│   │   ├── WifiTransferDialog.kt  # Fullscreen TV modal with QR code, timer, and preview controls
│   │   └── QrCodeGenerator.kt     # High-contrast ZXing QR code bitmap generator
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

---

## 8. Wi-Fi HTML Transfer Subsystem & Lifecycle

To solve the absence of the Android Storage Access Framework (`DocumentsUI`) on Google TV and Android TV, ScreenSmithy provides a zero-install, browser-based peer transfer subsystem over the local Wi-Fi network.

```mermaid
sequenceDiagram
    autonumber
    actor Admin as Administrator (TV)
    actor Sender as Browser (Phone/PC)
    participant Dialog as WifiTransferDialog
    participant Mgr as WifiTransferManager
    participant Server as WifiTransferHttpServer
    participant Importer as LocalHtmlImporter
    participant Main as MainActivity
    participant WV as KioskWebView

    Admin->>Dialog: Select "Transfer from Another Device"
    Dialog->>Mgr: startSession()
    Mgr->>Mgr: Resolve local IPv4 (NetworkUtils)
    Mgr->>Mgr: Generate 6-digit pairing code (SecureRandom)
    Mgr->>Server: start() [bind to local IP:8080..8100, SO_REUSEADDR]
    Mgr->>Dialog: Session state (IP, Port, Code, QR, 5m Timer)
    Dialog->>Admin: Display QR code, pairing code, and countdown timer

    Sender->>Server: GET / (Scan QR or navigate to TV IP)
    Server-->>Sender: 200 OK (Embedded Responsive Sender Web App)
    
    Sender->>Sender: Enter 6-digit pairing code & select HTML file (≤ 25 MB)
    Sender->>Server: POST /api/upload (Multipart stream + X-Pairing-Code)
    
    Server->>Server: Validate Host header (DNS rebinding guard)
    Server->>Server: Verify pairing code (MessageDigest.isEqual constant-time)
    alt Invalid Pairing Code
        Server->>Mgr: onPairingAttemptFailed()
        Server-->>Sender: 401 Unauthorized
        Note over Mgr: Track failed attempts; lock out after 5 failures
    else Valid Code
        Server->>Server: Stream multipart directly to staging disk file
        Server->>Server: Validate file extension (.html/.htm) and content structure
        Server-->>Sender: 200 OK {"success":true}
        Server->>Mgr: onUploadCompleted()
        Note over Server: Server stops immediately; closes port
        Mgr->>Dialog: State.Completed(stagedFile)
        
        Dialog->>Importer: importStagedFile(stagedFile, fileName)
        Importer->>Importer: Atomic rename to app-private storage: imported_html/<id>/<name>.html
        Importer->>Importer: Compute SHA-256 & persist LocalHtmlMetadata
        Importer-->>Dialog: ImportResult.Success(metadata)
        Dialog->>Admin: Display uploaded file info, "Preview Content", "Confirm & Activate"
        
        alt Admin clicks "Preview Content"
            Admin->>Dialog: Click "Preview Content"
            Dialog->>Main: onPreviewRequested(LocalHtml, policy)
            Dialog->>Dialog: dismiss()
            Main->>WV: loadContent(LocalHtml, policy)
            Main->>Admin: Display Fullscreen Preview with Top Preview Bar
            Admin->>Main: Click "Confirm & Activate" on Preview Bar
            Main->>Main: updateConfiguration(isSetupCompleted = true)
            Main->>WV: loadContent(LocalHtml, policy)
            Main->>Admin: Fullscreen Live Kiosk Presentation
        else Admin clicks "Confirm & Activate" directly
            Admin->>Dialog: Click "Confirm & Activate"
            Dialog->>Main: onActivationConfirmed(LocalHtml)
            Dialog->>Dialog: dismiss()
            Main->>Main: updateConfiguration(isSetupCompleted = true)
            Main->>WV: loadContent(LocalHtml, policy)
            Main->>Admin: Fullscreen Live Kiosk Presentation
        else Admin cancels or session expires
            Dialog->>Mgr: stopSession()
            Dialog->>Dialog: dismiss()
            Note over Main: Preserve previous working kiosk configuration
        end
    end
```

### 8.1 Key Subsystem Components & Responsibilities

1. **`NetworkUtils`**:
   - Resolves the TV's primary IPv4 address on active `wlan*` or `eth*` network interfaces, filtering out loopback and virtual interfaces.
   - Allocates an available TCP port in the preferred range (`8080`..`8100`) using `SO_REUSEADDR` to avoid `BindException` caused by sockets in `TIME_WAIT`.

2. **`WifiTransferSession` & `WifiTransferManager`**:
   - Manages the lifecycle and state transitions (`WaitingForConnection`, `ReceivingUpload`, `Validating`, `Completed`, `Failed`, `Cancelled`).
   - Generates a cryptographically random 6-digit numeric pairing code using `java.security.SecureRandom`.
   - Maintains a 5-minute session countdown ticker via Kotlin coroutines.
   - Enforces brute-force lockout: session shuts down if 5 failed pairing attempts occur.

3. **`WifiTransferHttpServer`**:
   - Built on standard Java `ServerSocket` without external server frameworks.
   - Bound strictly to the resolved local IP (never `0.0.0.0`).
   - Defends against DNS rebinding by validating the `Host` request header against the bound IP and port.
   - Performs constant-time pairing code comparison using `MessageDigest.isEqual` to prevent timing attacks.
   - Enforces a 25 MB request body limit to prevent memory or disk exhaustion.

4. **`MultipartStreamHandler`**:
   - Streams multipart form data chunks directly from the socket input stream into a temporary file in `imported_html/staging/`.
   - Never loads the complete file into JVM heap RAM, ensuring stability on memory-constrained Android TV hardware.

5. **`SenderHtmlTemplate`**:
   - Self-contained HTML5/CSS3/JavaScript web page embedded directly within the application binary.
   - Zero external CDN dependencies (works entirely offline on air-gapped local Wi-Fi networks).
   - Responsive dark-mode styling with client-side file-size and file-type validation, progress bar, and user-friendly error alerts.

6. **`LocalHtmlImporter.importStagedFile` & `WebViewAssetLoader`**:
   - Verifies the uploaded document contains valid HTML tags (`<html>`, `<body>`, `<!doctype`).
   - Generates a unique document directory: `context.filesDir/imported_html/<documentId>/<sanitizedFileName>.html`.
   - Atomically moves the staged file into its final document directory using `File.renameTo()`.
   - Computes SHA-256 checksum and saves metadata.
   - Served in `KioskWebView` via `androidx.webkit.WebViewAssetLoader` at `https://appassets.androidplatform.net/imported_content/<documentId>/<file>.html`.

7. **Preview Bar & Atomic Activation**:
   - Supports explicit pre-activation review: clicking **Preview Content** launches the sandboxed WebView with a high-contrast top **Preview Bar** (`Confirm & Activate`, `Exit Preview`).
   - If confirmed, `ConfigurationRepository.updateConfiguration` atomically commits the new `ContentSource.LocalHtml`, marks `isSetupCompleted = true`, and enters live kiosk presentation.
   - If cancelled or if the preview fails, the previous working configuration is preserved without disruption.

---

## 9. Global Display Margins & Overscan Protection Architecture

Consumer and commercial televisions commonly exhibit hardware overscan—an intentional legacy enlargement of the incoming display signal that crops 2% to 5% of pixels beyond the physical display bezel.

ScreenSmithy incorporates an authoritative application-level presentation container and margin applicator that insets foreground UI across all application-owned screens while preserving edge-to-edge background rendering.

### 9.1 Shared Layout & Inset Strategy
- **Background Canvas**: The application root container (`mainRoot` in `activity_main.xml`) fills 100% of the display window (`MATCH_PARENT`), maintaining `@color/bg_dark` edge-to-edge. No letterboxing or raw black bars are injected.
- **Content Insetting**: `DisplayMarginApplicator.applyMargins(view, margins)` computes pixel insets dynamically:
  $$\text{insetPx} = \text{round}(\text{dimensionPx} \times \frac{\text{percent}}{100})$$
- **System Bar Inset Coordination**: To avoid double-applying insets when system bars are visible, `DisplayMarginApplicator` queries `ViewCompat.getRootWindowInsets(view)` and applies:
  $$\text{effectiveInset} = \text{basePadding} + \max(\text{systemBarInset}, \text{marginInset})$$
- **Dialog Root Containers**: Application modals (`AdminPinDialog`, `AdminDashboardDialog`, `DisplayMarginsDialog`, `WifiTransferDialog`, `DiagnosticLogsDialog`) apply authoritative display margins to their root view in `onViewCreated` with `basePaddingDp = 24`. The dialog window occupies 100% of the display (`MATCH_PARENT`) so the dim scrim (`@color/dialog_scrim`) darkens the entire TV display edge-to-edge, while the interactive card remains safely inside the visible safe zone.

### 9.2 Domain Model & Persistence
- **Model (`com.screensmithy.app.domain.model.DisplayMargins`)**:
  - Independent `topPercent`, `bottomPercent`, `leftPercent`, `rightPercent`.
  - Invariants: $0 \le \text{percent} \le 20$, $\text{left} + \text{right} \le 40$, $\text{top} + \text{bottom} \le 40$.
  - Step size: $1\%$.
- **Persistence (`DataStoreConfigurationRepository`)**:
  - Stored under `KEY_DISPLAY_MARGIN_TOP`, `KEY_DISPLAY_MARGIN_BOTTOM`, `KEY_DISPLAY_MARGIN_LEFT`, `KEY_DISPLAY_MARGIN_RIGHT` within `DisplayPreferences`.
  - Missing or corrupted values fall back fail-safe to `DisplayMargins.ZERO`.
  - Modifying display margins does not touch content URLs, navigation policies, or admin security credentials.

### 9.3 Live Preview & Rollback
- In `DisplayMarginsDialog`, remote D-pad steppers trigger `MainActivity.previewDisplayMargins(draftMargins)` in real time.
- If the administrator cancels or dismisses the dialog without saving, `onDismiss` automatically invokes `MainActivity.restorePersistedDisplayMargins()`, reverting the display layout seamlessly without leaving draft artifacts.

### 9.4 Responsive Native Layout & Viewport Sizing
When global display margins are applied, root view containers (`dialogDashboardRoot`, `dialogPinRoot`, `dialogAdminRoot`, etc.) are inset by percentage margins plus base padding (`basePaddingDp = 24`), reducing the available content rectangle inside the container.

To prevent horizontal clipping or negative positioning offsets:
- Native dialog containers (`scrollDashboard`, `cardPin`, `scrollAdminContent`, `cardLogs`, `scrollWifiTransfer`, `cardMargins`, `scrollWizard`) use `android:layout_width="0dp"` with `app:layout_constrainedWidth="true"` and `app:layout_constraintWidth_max="<MAX_DP>"`.
- This ensures the view expands up to its designed maximum width on spacious displays, while shrinking responsively to fit within the reduced available content area when margins are configured, eliminating negative horizontal offset and left-edge clipping.
- In `AdminPinDialog`, `cardPin` enforces `app:layout_constrainedHeight="true"` enclosing a `NestedScrollView` (`scrollPin`), with optimized keypad button dimensions (`68dp × 46dp`, `3dp` margins). This guarantees all PIN indicators, numeric keypad buttons, Clear, Delete, Cancel, and Submit controls remain fully visible without clipping across all supported display sizes (including 720p and 1080p) and asymmetric margin configurations.

---

## 10. Admin Panel Lifecycle & Scroll/Focus Management

### 10.1 Initial Scroll Offset & Focus Restoration
On large TV displays, the Admin Dashboard contains extensive diagnostics and settings rows enclosed within a scrollable container (`NestedScrollView` `scrollDashboard`).

- **Previous Defect**: Calling `requestFocus()` on `btnReloadLiveContent` during initialization forced `NestedScrollView` to automatically scroll to the bottom of the card, hiding the title, active session badge, and primary diagnostic status.
- **Resolution**:
  1. `btnReloadLiveContent.requestFocus()` removed.
  2. `resetScrollAndInitialFocus()` posted to the view message queue after layout.
  3. Container explicitly scrolls to `(0, 0)`:
     ```kotlin
     binding.scrollDashboard.scrollTo(0, 0)
     ```
  4. Initial focus is assigned to `btnConfigureDefaultLauncher` (or `btnToggleKeepAwake`), the first enabled interactive control at the top of the card.
  5. Subsequent async data refreshes (such as network interface resolution or diagnostics loading) preserve the current user scroll position without jumping.


