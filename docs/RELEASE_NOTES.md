# ScreenSmithy — Release Notes & Verification Matrix

## Version 1.0.0 (Release Candidate)

**Date**: October 2026  
**Target Platform**: Android TV / Google TV / Android Displays (Android 9.0+ / API 28+, Target SDK 34)  
**License**: Apache License 2.0  

---

## 1. Feature Summary

ScreenSmithy v1.0.0 delivers a complete, secure, remote-controlled kiosk presentation platform for Android TV devices:

1. **Dual Content Sources**:
   - **Local HTML5**: Safe Storage Access Framework (SAF) import into app-private storage. Rendered via `WebViewAssetLoader` with strict sandboxing.
   - **Remote Web URLs**: Validated HTTP/HTTPS web application rendering with configurable navigation policies (`CONFIGURED_HOST_ONLY`, `ALLOWLIST_ONLY`, `ANY_HTTP_HOST`).
2. **Hidden Administration**:
   - Zero visible administrative affordances on the normal presentation screen.
   - 9-step hidden remote sequence: `UP UP DOWN DOWN LEFT RIGHT LEFT RIGHT OK`.
   - PBKDF2WithHmacSHA256 salted PIN protection (6–12 digits).
   - Progressive rate-limiting lockout protecting against brute-force attempts.
3. **Admin Dashboard & Live Diagnostics**:
   - Fullscreen TV dashboard with D-pad navigation and visible focus highlights.
   - Display toggles: Keep Screen Awake (`FLAG_KEEP_SCREEN_ON`), Sticky Immersive Mode, Reload on Network Restored.
   - In-memory circular diagnostic logger with automated secret sanitization and JSON export.
4. **Idempotent Startup & Unattended Recovery**:
   - Idempotent presentation coordinator drops duplicate loads across TV lifecycle events (`onResume`, `onNewIntent`, `onWindowFocusChanged`).
   - Bounded exponential backoff retry (5s, 10s, 20s, 40s, 60s) with $\pm 15\%$ random jitter.
   - Strict fail-closed TLS certificate validation.
   - Automatic Chromium renderer crash recovery with 60s crash-loop mitigation (max 3 crashes).
   - Network connectivity restoration handling via `ConnectivityManager.NetworkCallback`.
5. **Operating Modes**:
   - **Standard Normal Mode**: Runs as a standard TV application or Home launcher (`CATEGORY_HOME` + `CATEGORY_DEFAULT`).
   - **Managed Device Owner Mode**: Enterprise Lock Task Mode (`LOCK_TASK_MODE_LOCKED`), persistent default Home assignment, and keyguard/status bar suppression with authenticated maintenance exit path.

---

## 2. Test Execution & Build Verification Matrix

### Automated Test Metrics
| Suite | Tests Executed | Passed | Failed | Code Area |
|---|---|---|---|---|
| `DomainModelTest` | 13 | 13 | 0 | Model validation and immutability |
| `UrlValidatorTest` | 19 | 19 | 0 | Strict URL validation and normalization |
| `NavigationPolicyEvaluatorTest` | 15 | 15 | 0 | Policy evaluation and domain allowlisting |
| `LocalHtmlImporterTest` | 8 | 8 | 0 | SAF import, path safety, and sizing |
| `StartupDecisionEngineTest` | 14 | 14 | 0 | State evaluation and schema migration |
| `RemoteSequenceDetectorTest` | 7 | 7 | 0 | Directional sequence detection and timeout |
| `PinSecurityTest` | 12 | 12 | 0 | PBKDF2 hashing, salting, and lockout |
| `SetupWizardManagerTest` | 12 | 12 | 0 | Multi-step setup wizard transitions |
| `DiagnosticLoggerTest` | 13 | 13 | 0 | Secret sanitization and circular logging |
| `DeviceOwnerPolicyManagerTest` | 27 | 27 | 0 | Device Owner policies and Lock Task guards |
| `ErrorClassifierTest` | 6 | 6 | 0 | Transient vs fatal error classification |
| `BackoffCalculatorTest` | 5 | 5 | 0 | Bounded exponential backoff and jitter |
| `StartupRecoveryCoordinatorTest` | 7 | 7 | 0 | Idempotency, retry limits, and crash loops |
| *Previous Integration Suites* | 55 | 55 | 0 | Core app integration and helpers |
| **Total Automated Tests** | **213** | **213** | **0** | **100% Pass Rate** |

### Build Quality Checks
- `./gradlew testDebugUnitTest`: **BUILD SUCCESSFUL** (213 unit tests passed)
- `./gradlew lintDebug`: **BUILD SUCCESSFUL** (0 lint errors)
- `./gradlew assembleDebug`: **BUILD SUCCESSFUL** (APK generated)

---

## 3. Physical Hardware Verification

- **Device**: Google TV Streamer (`58141HFAG15D0Q`)
- **OS**: Android 14 (API 34)
- **WebView Engine**: `com.google.android.webview` v149.0.7827.91
- **Hardware Verification Scope**:
  - Cold boot launch and fullscreen presentation of remote URL (`https://sumitujjwal.github.io/`).
  - Idempotent lifecycle verified (no flicker or redundant load on intent redelivery).
  - Secret remote sequence (`UP UP DOWN DOWN LEFT RIGHT LEFT RIGHT OK`) verified using Android TV remote keys.
  - PIN gate authentication verified on physical TV screen.
  - Admin Dashboard display toggles and System Diagnostics verified on physical TV screen.
  - D-pad remote focus states and high-contrast focus rings verified.
  - Back key navigation: navigates within page history; safely preserves presentation on start page.

---

## 4. Final Implementation & Verification Status Checklist

### A. Implemented & Verified by Automated Tests
- [x] Complete domain models (`ContentSource`, `NavigationPolicy`, `RetryPolicy`, `DisplayPreferences`, `KioskConfiguration`).
- [x] Jetpack DataStore serialization and transactional configuration updates.
- [x] Pure URL validation, scheme restrictions, and navigation policy evaluation.
- [x] Safe local HTML archive validation and app-private sandboxed extraction.
- [x] PBKDF2WithHmacSHA256 salted PIN hashing and exponential brute-force lockout.
- [x] Remote sequence detector with 3-second sliding window.
- [x] Error classifier, bounded exponential backoff calculator with random jitter, and recovery coordinator.
- [x] Device Owner policy manager abstractions and Lock Task eligibility guards.
- [x] Secret sanitization for log records and diagnostic export.

### B. Manually Verified on Physical Target Hardware (Google TV Streamer)
- [x] Cold app startup and fullscreen sticky immersive display presentation.
- [x] Remote sequence entry via TV remote D-pad.
- [x] On-screen PIN entry dialog navigation and authentication.
- [x] Admin dashboard navigation, diagnostics inspection, and toggle persistence.
- [x] Idempotent intent handling on TV input switching.
- [x] Standard Normal Mode reporting and Home launcher guidance.

### C. Documented for Manual Deployment / Production Setup
- [x] Full Device Owner QR code / factory reset provisioning on clean devices (documented in `DEVICE_OWNER_SETUP.md`).
- [x] Hardware reboot behavior under Device Owner mode.
- [x] Production release APK signing with `apksigner`.

### D. Architectural Limitations & Unsupported Capabilities
- **Branded OEM Remote Buttons (Normal Mode)**: Consumer TV firmware handles dedicated buttons (Netflix, YouTube, etc.) at the system level before app dispatch. Suppressing branded buttons requires **Device Owner Mode** with active Lock Task mode.
- **Single-File HTML Asset Resolution**: Importing a single `.html` file via SAF imports that file only. Complex multi-file web applications should bundle styles and scripts inline or use remote web URLs.
- **TLS Certificate Bypass**: Self-signed or untrusted SSL certificates are strictly rejected (fail-closed).
- **Out of Scope**: Cloud fleet management, MDM backend servers, PDF viewers, video playlists, and user accounts.

---

## 5. Artifact Output & Production Signing Guide

### Generated Debug APK
- **File**: `app/build/outputs/apk/debug/app-debug.apk`
- **Package ID**: `com.screensmithy.app`
- **Version Code**: `1`
- **Version Name**: `1.0.0`

### Production Release Signing Procedure
To produce an official signed release APK without storing secrets in version control:

1. **Generate a Private Release Keystore** (run once on a secure offline machine):
   ```bash
   keytool -genkeypair -v \
     -keystore screensmithy-release.jks \
     -alias screensmithy \
     -keyalg RSA \
     -keysize 4096 \
     -validity 10000 \
     -deststoretype JKS
   ```

2. **Build the Unsigned Release Bundle / APK**:
   ```bash
   ./gradlew assembleRelease
   ```
   *Output*: `app/build/outputs/apk/release/app-release-unsigned.apk`

3. **Align and Sign with Android SDK `apksigner`**:
   ```bash
   # 1. Align the APK on 4-byte boundaries
   zipalign -v -p 4 \
     app/build/outputs/apk/release/app-release-unsigned.apk \
     screensmithy-v1.0.0-aligned.apk

   # 2. Sign with release key (v2 + v3 scheme enabled)
   apksigner sign \
     --ks screensmithy-release.jks \
     --ks-key-alias screensmithy \
     --out screensmithy-v1.0.0-signed.apk \
     screensmithy-v1.0.0-aligned.apk

   # 3. Verify signature
   apksigner verify --verbose screensmithy-v1.0.0-signed.apk
   ```

> [!CAUTION]
> Never commit `*.jks`, `*.keystore`, or key passwords to version control. Keep backup copies of your release keystore in secure offline storage.
