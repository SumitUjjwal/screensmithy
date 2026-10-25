package com.screensmithy.app.domain.diagnostics

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class DiagnosticReportExporterTest {

    private val sampleSnapshot = SystemDiagnosticSnapshot(
        timestampEpochMs = 1700000000000L,
        appVersion = "1.0.0",
        appVersionCode = 1L,
        osVersion = "Android 14 (API 34)",
        deviceModel = "Google TV Streamer",
        isLeanbackTv = true,
        webViewPackage = "com.google.android.webview v120.0.6099.144",
        operatingMode = "Standard Mode (Normal Android TV Installation)",
        isDeviceOwner = false,
        isProfileOwner = false,
        lockTaskState = "None (Not active)",
        defaultHomeLauncher = DefaultLauncherStatus.No("com.google.android.apps.tv.launcherx"),
        networkStatus = NetworkDiagnosticStatus(
            isConnected = true,
            transportType = "Wi-Fi",
            isValidated = true
        ),
        contentSourceType = "Remote Web URL",
        sanitizedContentIdentifier = "https://kiosk.example.com/?token=REDACTED",
        isSetupCompleted = true,
        navigationMode = "CONFIGURED_HOST_ONLY",
        keepScreenAwake = true,
        immersiveSticky = true,
        reloadOnNetworkRestored = true,
        lastLoadAttemptEpochMs = 1699999900000L,
        lastLoadSuccess = true,
        lastErrorMessage = null
    )

    private val sampleLogs = listOf(
        DiagnosticLogEntry(
            id = 1L,
            timestampEpochMs = 1699999800000L,
            level = LogLevel.INFO,
            category = LogCategory.STARTUP,
            message = "App cold started in Standard Mode"
        ),
        DiagnosticLogEntry(
            id = 2L,
            timestampEpochMs = 1699999900000L,
            level = LogLevel.INFO,
            category = LogCategory.RENDERER,
            message = "Loaded content successfully",
            details = "URL: https://kiosk.example.com/?token=REDACTED"
        )
    )

    @Test
    fun generateJsonReport_containsAllRequiredSections() {
        val exporter = DiagnosticReportExporter(timeProvider = { 1700000000000L })
        val jsonString = exporter.generateJsonReport(sampleSnapshot, sampleLogs)

        val json = JSONObject(jsonString)
        assertEquals(1, json.getInt("reportSchemaVersion"))
        assertTrue(json.has("exportedAt"))

        // Environment
        val env = json.getJSONObject("environment")
        assertEquals("ScreenSmithy", env.getString("application"))
        assertEquals("1.0.0", env.getString("versionName"))
        assertEquals(1L, env.getLong("versionCode"))
        assertEquals("Google TV Streamer", env.getString("deviceModel"))
        assertTrue(env.getBoolean("isLeanbackTv"))

        // Capabilities & Mode
        val caps = json.getJSONObject("capabilitiesAndMode")
        assertEquals("Standard Mode (Normal Android TV Installation)", caps.getString("operatingMode"))
        assertFalse(caps.getBoolean("isDeviceOwner"))
        assertEquals("None (Not active)", caps.getString("lockTaskState"))
        assertTrue(caps.getString("defaultLauncherStatus").startsWith("NO"))

        // Configuration
        val config = json.getJSONObject("configuration")
        assertTrue(config.getBoolean("isSetupCompleted"))
        assertEquals("https://kiosk.example.com/?token=REDACTED", config.getString("sanitizedContentIdentifier"))
        assertTrue(config.getBoolean("keepScreenAwake"))
        assertTrue(config.getBoolean("immersiveSticky"))
        assertTrue(config.getBoolean("reloadOnNetworkRestored"))

        // Network
        val net = json.getJSONObject("network")
        assertTrue(net.getBoolean("isConnected"))
        assertEquals("Wi-Fi", net.getString("transportType"))

        // Logs
        val logs = json.getJSONArray("recentDiagnosticLogs")
        assertEquals(2, logs.length())
        assertEquals("App cold started in Standard Mode", logs.getJSONObject(0).getString("message"))
    }

    @Test
    fun exportToStream_writesValidUtf8Json() {
        val exporter = DiagnosticReportExporter(timeProvider = { 1700000000000L })
        val outputStream = ByteArrayOutputStream()

        exporter.exportToStream(sampleSnapshot, sampleLogs, outputStream)
        val result = outputStream.toString(Charsets.UTF_8.name())

        assertTrue(result.startsWith("{"))
        assertTrue(result.endsWith("}"))
        val json = JSONObject(result)
        assertEquals("ScreenSmithy", json.getJSONObject("environment").getString("application"))
    }
}
