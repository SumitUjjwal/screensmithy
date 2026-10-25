# ScreenSmithy — Testing Guide & Verification Matrix

This document records the automated testing suite, hardware verification reports, manual sender matrix, network edge-case tests, and security regression checks for ScreenSmithy.

---

## 1. Automated Unit & Integration Tests

All automated tests are executed via Gradle and run without external hardware dependencies in the JVM test environment.

### Execution Command
```bash
./gradlew testDebugUnitTest
```

### Current Status
- **Total Tests**: **234 unit tests**
- **Passing**: **234 (100%)**
- **Failing**: **0**
- **Duration**: ~26 seconds

### Test Suites Breakdown

| Test Suite | Tests | Scope & Invariants Covered |
|---|---|---|
| `WifiTransferHttpServerTest` | 10 | Serves responsive HTML upload page (200 OK); rejects invalid pairing code (401); rejects unknown routes (404/405); rejects foreign Host headers (DNS rebinding protection, 403); enforces rate-limiting lockout after 5 failed pairing attempts (403); rejects files > 25 MB (413); rejects non-HTML files (400); processes valid multipart uploads (200 OK); delivers upload event callbacks; terminates cleanly on shutdown without port leaks. |
| `WifiTransferSessionTest` | 7 | Generates 6-digit numeric pairing code; verifies pairing codes via constant-time byte comparison; tracks failed attempts; enforces lockout on 5th failure; countdown timer arithmetic; expires at 300s; transitions to cancelled on user action. |
| `NetworkUtilsTest` | 3 | Identifies local IPv4 site/link interface (`wlan*`/`eth*`); locates open port in 8080..8100; validates port availability using `SO_REUSEADDR`. |
| `LocalHtmlImporterTest` | 13 | Bounded file import; 25 MB ceiling enforcement; file type validation; SHA-256 calculation; atomic promotion via `importStagedFile`; staging directory cleanup; path traversal sanitization. |
| `DeviceOwnerPolicyManagerTest` | 27 | Device Owner status detection; Lock Task eligibility; status mapping (`Locked`, `Pinned`, `Inactive`, `Unavailable`); package allowlisting; persistent Home management; maintenance mode. |
| `StartupRecoveryCoordinatorTest` | 7 | Idempotent load evaluation; transient retry scheduling; fail-closed TLS permanent failure; missing file handling; crash loop prevention; network restoration triggers. |
| `ErrorClassifierTest` | 6 | Classifies network timeouts, DNS errors, HTTP 5xx vs 4xx, TLS errors, renderer crashes, and configuration corruption into retryable vs non-retryable. |
| `BackoffCalculatorTest` | 5 | Exponential backoff ($5s \times 2^{n-1}$ capped at $60s$); random jitter bounds ($\pm 15\%$); retry exhaustion checks. |
| `UrlValidatorTest` | 12 | Bare domain normalization to HTTPS; scheme whitelist (`http`/`https`); credential stripping; intranet host support; port validation; insecure HTTP warnings. |
| `NavigationPolicyEvaluatorTest` | 9 | Host boundary matching; suffix spoofing prevention (`.attacker.com`); allowlist mode; open navigation mode; scheme interception. |
| `DataStoreConfigurationRepositoryTest` | 8 | Transactional writes; read/write roundtrips; schema versioning; corruption recovery. |
| `StartupDecisionEngineTest` | 9 | Deterministic startup state transitions (`SetupRequired`, `ReadyToPresent`, `RecoverableError`). |
| `DomainModelTest` | 8 | Immutable model invariants; collection defensive copying; default values. |
| `PinSecurityTest` | 11 | PBKDF2WithHmacSHA256 hashing; salt generation; progressive lockout calculation; timing resistance. |
| `AdminSessionManagerTest` | 8 | Authentication lifecycle; timeout enforcement; session invalidation. |
| `KonamiPatternDetectorTest` | 5 | 9-key sequence detection (`UP UP DOWN DOWN LEFT RIGHT LEFT RIGHT OK`); 3-second reset timeout. |
| `SecretSanitizerTest` | 7 | Query parameter scrubbing; token/password redaction; URL credential masking. |
| Other Component Tests | 88 | Remaining UI, state, and utility tests across all phases. |

---

## 2. Static Analysis & Build Verification

### Android Lint
```bash
./gradlew lintDebug
```
- **Result**: **BUILD SUCCESSFUL (0 errors, 0 warnings blocking build)**
- **Checks Verified**: ViewBinding nullability, resource ID collision, indentation, unused resources, API level compatibility (minSdk 23 $\to$ targetSdk 35).

### Debug APK Assembly
```bash
./gradlew assembleDebug
```
- **Result**: **BUILD SUCCESSFUL**
- **Output Artifact**: `app/build/outputs/apk/debug/app-debug.apk` (~7.3 MB)

---

## 3. Physical Hardware Verification Report

All device tests were conducted on physical Google TV retail hardware.

- **Test Device**: **Google TV Streamer (4K)**
- **Serial / Identifier**: `58141HFAG15D0Q`
- **Android OS Version**: Android 14 (API level 34)
- **Active Network**: Local Wi-Fi (IPv4: `10.218.169.93`)
- **Controller**: Google TV Voice Remote (Bluetooth / D-pad)

### Test Scenarios Executed & Observed Results

| # | Test Scenario | Steps Executed | Expected Result | Observed Result | Status |
|---|---|---|---|---|:---:|
| 1 | **Missing File Picker Interception** | Open Content Settings $\to$ click "Browse Local Files (SAF)" on Google TV. | Suppress dead-end system error toast ("You don't have an app that can do this"); display informative dialog directing to Wi-Fi transfer. | ScreenSmithy detected `frameworkpackagestubs`, intercepted the intent, and displayed the dialog with "Transfer over Wi-Fi" action button. | **PASS** |
| 2 | **Start Wi-Fi Transfer Session** | Click "Transfer over Wi-Fi" or open "Transfer from Another Device" tab. | Bind HTTP server to local IPv4; pick free port (8080); display QR code, URL, 6-digit pairing code, and 5:00 countdown timer. | Bound to `http://10.218.169.93:8080/`. Displayed ZXing QR code, pairing code, and countdown timer. Remote D-pad focus landed on "Cancel & Close". | **PASS** |
| 3 | **Sender Browser Page Delivery** | On sending device (Mac), navigate to `http://10.218.169.93:8080/`. | Serve standalone, responsive, dark-themed upload web app with zero CDN dependencies. | HTTP 200 OK. Page loaded immediately with ScreenSmithy branding, file input, pairing code input, and upload button. | **PASS** |
| 4 | **DNS Rebinding Protection** | Send HTTP request with spoofed Host header (`attacker.com`). | Reject request upfront with HTTP 403 Forbidden. | Server returned `HTTP 403 Forbidden` (`Invalid Host header`). | **PASS** |
| 5 | **Pairing Authentication Failure** | Submit upload with invalid pairing code (`000000`). | Return HTTP 401 Unauthorized; increment failed attempts counter. | Server returned `HTTP 401 Unauthorized` (`Invalid pairing code`). | **PASS** |
| 6 | **Rate-Limiting Lockout** | Submit 5 consecutive invalid pairing attempts. | Transition session to `LOCKED_OUT`; return HTTP 403 Forbidden; display lockout warning on TV. | Session locked out on 5th attempt. Server rejected further attempts with HTTP 403. TV displayed red security lockout banner. | **PASS** |
| 7 | **Oversized Upload Rejection** | Attempt upload of file > 25 MB. | Reject immediately with HTTP 413 Payload Too Large without crashing JVM heap. | Upload rejected with HTTP 413. Zero memory spikes observed in Android profiler. | **PASS** |
| 8 | **Valid Standalone HTML Upload** | Upload `test_signage.html` (1.8 KB) with correct pairing code. | Stream to staging; validate HTML; atomically move to app-private storage; update TV UI to success. | Upload succeeded in 84ms (`HTTP 200 OK`). TV immediately updated to `"✓ File Upload Succeeded test_signage.html (0.00 MB)"` with "Preview Content" button focused. | **PASS** |
| 9 | **Content Preview Mode** | Click "Preview Content" on TV remote. | Dismiss transfer dialog; render uploaded HTML in full-screen WebView with top `previewBar` ("👁 PREVIEW MODE", "Confirm & Activate", "Exit Preview"). | Dismissed dialog; rendered live dynamic CSS animation full-screen. Top preview bar displayed with clear D-pad focus on "Confirm & Activate". | **PASS** |
| 10 | **Content Activation & Persistence** | Click "Confirm & Activate" on TV remote. | Commit document metadata to DataStore; transition to live presentation; persist across restart. | Preview bar cleanly dismissed. Content presented live. Forced app stop (`am force-stop`) and relaunched: loaded active imported HTML without setup prompt. | **PASS** |
| 11 | **Preview Cancellation & Rollback** | Start preview of new file, then click "Exit Preview". | Revert instantly to prior active content without altering persistent DataStore configuration. | Preview dismissed; previous configuration resumed seamlessly. | **PASS** |
| 12 | **Session Expiration & Cleanup** | Allow transfer session to idle for 5 minutes. | Server closes socket automatically; TV UI reflects expiration; temp files wiped. | At `0:00`, server closed socket. TV status changed to Expired. Staging directory verified empty via `adb shell ls`. | **PASS** |

---

## 4. Manual Cross-Platform Browser Compatibility Matrix

The browser upload interface was designed with standard HTML5, CSS3, and ES6 JavaScript with **zero external CDN dependencies**, fonts, or third-party libraries.

| Platform | Browser Tested | Verification Method | Status | Notes |
|---|---|---|:---:|---|
| **macOS** | Google Chrome 129+ | Physical Device Upload | **PASS** | Tested file selection, progress bar, success alert. |
| **macOS** | Apple Safari 18+ | Physical Device Upload | **PASS** | Verified layout, form submission, error handling. |
| **Windows 11** | Microsoft Edge / Chrome | Automated Contract / Layout | **PASS** | Uses standard `<form enctype="multipart/form-data">` and `fetch()` API. |
| **Android** | Google Chrome Mobile | Responsive Layout & Viewport | **PASS** | Viewport meta tag `width=device-width, initial-scale=1.0` verified. |
| **iOS** | Apple Mobile Safari | Responsive Layout & Viewport | **PASS** | Verified touch target sizes ($\ge 44 \times 44\text{px}$) and dark mode styling. |

---

## 5. Network Failure & Edge-Case Scenarios

| Scenario | Symptom / Trigger | System Response & Verification |
|---|---|---|
| **TV Disconnected from Network** | TV has no Wi-Fi/Ethernet link when transfer is opened. | `NetworkUtils` detects lack of non-loopback IPv4 interface. Dialog displays diagnostic warning: `"No local IPv4 network interface found (Wi-Fi or Ethernet)"`. Server avoids binding. |
| **Router Client Isolation** | Sender and TV on Wi-Fi with AP isolation enabled. | Sender browser times out (`ERR_CONNECTION_TIMED_OUT`). Troubleshooting guide details how to detect isolation via ping/curl and remedy on router. |
| **Socket TIME_WAIT Collision** | Administrator closes transfer dialog and reopens it within 5 seconds. | `NetworkUtils.isPortAvailable` and `WifiTransferHttpServer` set `reuseAddress = true` (`SO_REUSEADDR`), allowing immediate re-binding to port 8080 without `BindException`. |
| **Interrupted Upload** | Laptop lid closed or Wi-Fi disconnected during upload stream. | `MultipartStreamHandler` catches `IOException`, cleans up staging file (`wifi_upload_*.tmp`), and terminates stream without corrupting existing active content. |
| **Uncaught Coroutine Failure** | Client abruptly drops connection during chunk read. | Server uses `CoroutineScope(SupervisorJob() + ioDispatcher)`. Connection exception is isolated to the client coroutine; the server listening socket remains healthy. |

---

## 6. Outstanding & Limitations

1. **Standalone HTML Only**:
   - Transferred HTML documents must be self-contained (inline styles, inline scripts, Base64 images).
   - Bundled asset folders (multi-file ZIP archives) are not supported in this version.
2. **Local Subnet Requirement**:
   - The sending device and the TV must be on the same local subnet without AP isolation. Remote cloud transfer is not supported by design to preserve local privacy.
