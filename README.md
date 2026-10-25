# ScreenSmithy

**Turn an Android TV or compatible Android display into a secure, dedicated digital kiosk.**

ScreenSmithy is an open-source, enterprise-grade Android kiosk application built specifically for Android TV, Google TV, and Android digital signage displays. It presents either a local HTML5 document or a remote web application in a seamless, fullscreen experience designed from the ground up for TV remote control, zero-touch recovery, and cryptographic administration.

---

## Key Features

- **Dual Content Presentation**:
  - **Local HTML5**: Offline HTML documents imported securely into app-private storage via the Android Storage Access Framework (SAF) and served sandboxed via `androidx.webkit.WebViewAssetLoader`.
  - **Remote Web URLs**: Dynamic HTTP/HTTPS dashboards and web apps with strict URL validation and domain navigation policies (`CONFIGURED_HOST_ONLY`, `ALLOWLIST_ONLY`, `ANY_HTTP_HOST`).
- **Hidden Administration & PIN Security**:
  - **Zero On-Screen Clutter**: No visible administration buttons, settings icons, or affordances on the live kiosk presentation.
  - **Secret Remote Sequence**: 9-step directional sequence on the TV remote (`UP` $\to$ `UP` $\to$ `DOWN` $\to$ `DOWN` $\to$ `LEFT` $\to$ `RIGHT` $\to$ `LEFT` $\to$ `RIGHT` $\to$ `OK`) invokes the admin gate.
  - **Cryptographic Security**: PBKDF2WithHmacSHA256 salted PIN hashing (6–12 digits) with zero plaintext storage and progressive brute-force rate-limiting lockout.
- **TV Remote & D-Pad Optimized**:
  - High-contrast visual focus indicators and custom D-pad navigation across all wizards and settings dialogs.
  - On-screen numeric keypad for seamless remote-control PIN entry.
- **Idempotent Lifecycle & Crash Resilience**:
  - Idempotent startup coordinator prevents redundant reloads across TV input switching and lifecycle events (`onResume`, `onNewIntent`, `onWindowFocusChanged`).
  - Bounded exponential backoff retry (5s, 10s, 20s, 40s, 60s) with $\pm 15\%$ random jitter.
  - Automatic Chromium renderer termination recovery with 60s crash-loop protection (max 3 crashes).
  - Network restoration detection via `ConnectivityManager.NetworkCallback` with automatic reload.
  - Strict fail-closed TLS/SSL policy (untrusted certificates are never bypassed).
- **Two Distinct Operating Modes**:
  - **Standard Normal Mode**: Installable as a standard APK. Integrates as an Android TV Home launcher (`CATEGORY_HOME` + `CATEGORY_DEFAULT`).
  - **Managed Device Owner Mode**: Hardware-enforced Lock Task Mode (`LOCK_TASK_MODE_LOCKED`), persistent Home registration, and system UI suppression for dedicated commercial kiosks.
- **Diagnostics & Data Protection**:
  - In-memory circular diagnostic logger with automated secret sanitization (redacts passwords, tokens, API keys).
  - Local diagnostic report export to JSON.
  - `android:allowBackup="false"` prevents unauthorized data extraction.

---

## Operating Modes

| Capability | Standard Normal Mode | Managed Device Owner Mode |
|---|---|---|
| **Installation** | Standard APK install via ADB or USB | Provisioned via ADB or QR code on unprovisioned/wiped device |
| **Lock Task Mode** | Not available (system screen pinning only) | Fully locked (`LOCK_TASK_MODE_LOCKED`) allowlisted by policy |
| **Home Launcher** | Registered as Home app in TV System Settings | Permanent default Home assigned via `DevicePolicyManager` |
| **System Key Suppression** | TV OS handles Home/Power/OEM buttons | System Home & Back locked to kiosk; status bar suppressed |
| **Maintenance Path** | Open TV Settings or Admin Dashboard | Authenticated `stopLockTask()` release in Admin Dashboard |

For complete instructions on provisioning a dedicated device, see [**`docs/DEVICE_OWNER_SETUP.md`**](docs/DEVICE_OWNER_SETUP.md).

---

## Prerequisites

- **Device**: Android TV, Google TV, or Android display running **Android 9.0+ (API 28+)**; target SDK is **Android 14 (API 34)**.
- **Input**: Android TV remote control, D-pad controller, or keyboard.
- **Development Workstation**:
  - JDK 17 or higher
  - Android SDK Tools (API 34)
  - Gradle 8.11+ (wrapper included)

---

## Building and Installing

### 1. Build the Debug APK
```bash
./gradlew clean test lint assembleDebug
```

### 2. Verify Automated Tests
ScreenSmithy includes 213 automated unit tests covering domain models, URL validation, HTML import, security hashing, recovery coordination, and device policies:
```bash
./gradlew testDebugUnitTest
```

### 3. Install on Android TV via ADB
Connect to your TV device over USB or Wi-Fi:
```bash
adb connect <TV_IP_ADDRESS>:5555
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
```

### 4. Launch ScreenSmithy
```bash
adb shell am start -n com.screensmithy.app/.MainActivity
```

---

## First-Run Setup & Administration

1. **Initial Setup Wizard**:
   - On fresh installation, ScreenSmithy opens the first-run setup wizard.
   - Choose your content type: **Remote Web URL** or **Local HTML Document**.
   - Configure a **6 to 12 digit Administrator PIN** (default for initial testing: `123456`).
   - Select your navigation policy (`CONFIGURED_HOST_ONLY`, `ALLOWLIST_ONLY`, or `ANY_HTTP_HOST`).
   - Test and preview the content before activating.
2. **Accessing the Admin Dashboard**:
   - On the live presentation screen, press the remote sequence:
     $$\text{UP} \to \text{UP} \to \text{DOWN} \to \text{DOWN} \to \text{LEFT} \to \text{RIGHT} \to \text{LEFT} \to \text{RIGHT} \to \text{OK}$$
   - Enter your PIN on the on-screen keypad and select **Submit**.
   - The **Admin Dashboard** provides live diagnostics, display toggles, content staging, PIN changes, and diagnostic log export.

---

## Repository Documentation

- [**System Architecture & Design**](docs/ARCHITECTURE.md): Deep dive into Clean Architecture layers, state machines, and Mermaid lifecycle diagrams.
- [**Device Owner & Managed Kiosk Setup**](docs/DEVICE_OWNER_SETUP.md): Step-by-step enterprise provisioning, Lock Task checks, and decommissioning guide.
- [**Security Architecture & Threat Model**](docs/SECURITY.md): Threat analysis covering 14 attack vectors, cryptographic hashing, and sandboxing rules.
- [**Troubleshooting & Diagnostic Guide**](docs/TROUBLESHOOTING.md): Solutions for network errors, SSL certificates, remote sequences, and TV sleep settings.
- [**Release Notes & Verification Matrix**](docs/RELEASE_NOTES.md): Test execution metrics, hardware verification reports, and production signing instructions.

---

## Platform Limitations & Considerations

- **Single-File Local HTML**: Importing an HTML document imports that single `.html` file into app storage. Embedded styles, inline scripts, and Base64-encoded images are recommended for standalone offline pages.
- **Branded OEM Remote Buttons (Normal Mode)**: Consumer TV remotes featuring dedicated branded buttons (Netflix, YouTube, Prime Video) send global hardware intents governed by the TV firmware. In Standard Mode, consumer remotes may exit the app. Suppressing branded buttons requires **Device Owner Managed Mode**.
- **TLS Certificate Fail-Closed**: Insecure self-signed or invalid HTTPS certificates are never bypassed. Production endpoints must use valid SSL/TLS certificates.

---

## Contributing

Contributions, bug reports, and documentation improvements are welcome! Please review [docs/SECURITY.md](docs/SECURITY.md) before submitting changes. Never commit signing keys, keystores, or credentials to version control.

---

## License

ScreenSmithy is open-source software licensed under the [Apache License 2.0](LICENSE).
