# ScreenSmithy

**Turn an Android TV or compatible Android device into a dedicated display.**

ScreenSmithy is an open-source Android kiosk application being built to display either a local HTML document or a web page in a fullscreen, TV-friendly experience. It is designed around remote-control navigation, PIN-protected administration, and a clear distinction between ordinary app installation and managed-device kiosk capabilities.

> **Project status: Work in progress.** The repository is being set up and the features below describe the intended scope. Do not treat ScreenSmithy as production-ready until the implementation, security review, and device testing are complete.

## Goals

- Display a locally imported `.html` or `.htm` file.
- Display a configured HTTP or HTTPS URL in Android WebView.
- Provide an admin area protected by a hidden remote-control sequence and an administrator PIN.
- Make setup and administration usable with a TV remote and D-pad.
- Recover gracefully from network failures, WebView crashes, and app restarts.
- Support ordinary APK installation with best-effort kiosk behavior.
- Support stronger kiosk lockdown on compatible, properly provisioned Device Owner devices.
- Keep configuration and administration local to the device; a cloud backend is not required for the initial scope.

## Planned features

- [ ] First-run setup wizard and persistent configuration
- [ ] Local HTML file selection and import into app-private storage
- [ ] URL validation and configurable web-navigation policy
- [ ] Fullscreen WebView presentation
- [ ] Hidden remote-control sequence and PIN-protected admin screens
- [ ] TV-remote-friendly setup, settings, and diagnostics
- [ ] Loading, offline, and content-error screens
- [ ] Bounded retry and WebView renderer recovery
- [ ] Normal-installation launcher guidance
- [ ] Device Owner detection and supported Lock Task Mode integration
- [ ] Automated tests, security documentation, and installation guides

See [GitHub Issues](https://github.com/SumitUjjwal/screensmithy/issues) for implementation tasks and discussion.

## Scope for the initial version

The first version focuses on **local HTML files and HTTP(S) URLs**. A local HTML file may depend on sibling images, scripts, fonts, or stylesheets; these assets need to be imported together or otherwise made available through supported paths. Importing a single HTML file will not automatically include adjacent files.

Features such as cloud management, remote device fleets, playlists, PDF rendering, and video signage are outside the initial scope.

## Kiosk modes and platform limitations

ScreenSmithy is intended to support two operating modes:

### Normal installation

The app can be installed as a regular APK and may be selected as the device's Home/launcher app where supported. Android does not let an ordinary app silently grant itself default-Home status or guarantee that it can prevent every exit path. Startup behavior and restrictions can vary by Android version and device manufacturer.

### Managed kiosk mode

On compatible devices provisioned by an authorized administrator as **Device Owner**, Android's supported device-policy APIs and Lock Task Mode can provide stronger kiosk restrictions. Device Owner provisioning is not granted by simply installing an APK and may require a factory-reset device. Provisioning and recovery instructions will be documented as the implementation matures.

The project will report actual device capabilities rather than claiming restrictions that are not active.

## Technology direction

The planned implementation uses:

- **Kotlin** for Android application code
- **Android Studio and Gradle Kotlin DSL** for development and builds
- **Android WebView** for rendering HTML and web content
- **AndroidX WebKit / WebViewAssetLoader**, where appropriate, for safer local-content loading
- **Jetpack DataStore** or an equivalent maintained option for configuration persistence
- **Android DevicePolicyManager** for supported managed-kiosk functionality

These are the intended implementation choices; the repository may evolve as compatibility and testing requirements become clear.

## Security principles

ScreenSmithy handles web content and imported HTML, so security is a core requirement rather than an optional enhancement.

- Never bypass TLS certificate errors.
- Validate URLs and allow only explicitly supported schemes.
- Avoid exposing privileged Android functionality to webpage JavaScript.
- Restrict local-content access to app-managed files.
- Store a salted PIN hash, never a plaintext PIN.
- Apply authentication rate limiting.
- Do not log PINs, credentials, cookies, tokens, or sensitive URL parameters.
- Keep normal-installation limitations distinct from Device Owner protections.

Please do not use this project to store or publish credentials, signing keys, real device secrets, or private configuration. If you discover a security issue, avoid posting sensitive exploit details publicly; use a private reporting channel if one is configured, or contact the maintainer before public disclosure.

## Development status

The repository currently contains the initial project documentation and license. The Android application implementation, build instructions, and test coverage are still being developed.

Once the Android project is added, this section will include verified requirements and commands for building, testing, installing, and running ScreenSmithy. Until then, build commands and APK paths are intentionally not listed as if they were available.

## Contributing

Contributions, bug reports, feature ideas, and documentation improvements are welcome.

1. Check existing [issues](https://github.com/SumitUjjwal/screensmithy/issues) before starting substantial work.
2. For larger features, open an issue first to discuss scope and implementation.
3. Keep changes focused and include tests for behavior where practical.
4. Never include secrets, signing credentials, or private customer/company data.
5. Clearly document any Android-version or device-manufacturer limitations.

Development and contribution instructions will be expanded when the initial application scaffold is available.

## License

ScreenSmithy is licensed under the [Apache License 2.0](LICENSE). See the license file for the terms and conditions.

## Links

- **Repository:** https://github.com/SumitUjjwal/screensmithy
- **Issues and feature requests:** https://github.com/SumitUjjwal/screensmithy/issues
