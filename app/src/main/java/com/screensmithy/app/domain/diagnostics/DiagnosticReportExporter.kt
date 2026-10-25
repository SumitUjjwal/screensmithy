package com.screensmithy.app.domain.diagnostics

import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Serializes and exports sanitized diagnostic system snapshots and logs.
 */
class DiagnosticReportExporter(
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) {

    fun generateJsonReport(
        snapshot: SystemDiagnosticSnapshot,
        logs: List<DiagnosticLogEntry>
    ): String {
        val root = JSONObject()

        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        root.put("reportSchemaVersion", 1)
        root.put("exportedAt", isoFormat.format(Date(timeProvider())))

        // Environment Block
        val env = JSONObject().apply {
            put("application", "ScreenSmithy")
            put("versionName", snapshot.appVersion)
            put("versionCode", snapshot.appVersionCode)
            put("osVersion", snapshot.osVersion)
            put("deviceModel", snapshot.deviceModel)
            put("isLeanbackTv", snapshot.isLeanbackTv)
            put("webViewPackage", snapshot.webViewPackage)
        }
        root.put("environment", env)

        // Capabilities & Mode
        val caps = JSONObject().apply {
            put("operatingMode", snapshot.operatingMode)
            put("isDeviceOwner", snapshot.isDeviceOwner)
            put("isProfileOwner", snapshot.isProfileOwner)
            put("lockTaskState", snapshot.lockTaskState)
            put(
                "defaultLauncherStatus",
                when (val dl = snapshot.defaultHomeLauncher) {
                    is DefaultLauncherStatus.Yes -> "YES"
                    is DefaultLauncherStatus.No -> "NO (current: ${dl.currentDefaultPackage ?: "unknown"})"
                    is DefaultLauncherStatus.Unknown -> "UNKNOWN"
                }
            )
        }
        root.put("capabilitiesAndMode", caps)

        // Configuration
        val config = JSONObject().apply {
            put("isSetupCompleted", snapshot.isSetupCompleted)
            put("contentSourceType", snapshot.contentSourceType)
            put("sanitizedContentIdentifier", snapshot.sanitizedContentIdentifier)
            put("navigationMode", snapshot.navigationMode)
            put("keepScreenAwake", snapshot.keepScreenAwake)
            put("immersiveSticky", snapshot.immersiveSticky)
            put("reloadOnNetworkRestored", snapshot.reloadOnNetworkRestored)
        }
        root.put("configuration", config)

        // Network Status
        val net = JSONObject().apply {
            put("isConnected", snapshot.networkStatus.isConnected)
            put("transportType", snapshot.networkStatus.transportType)
            put("isValidated", snapshot.networkStatus.isValidated)
        }
        root.put("network", net)

        // Runtime Status
        val runtime = JSONObject().apply {
            put("lastLoadAttempt", snapshot.lastLoadAttemptEpochMs?.let { isoFormat.format(Date(it)) } ?: JSONObject.NULL)
            put("lastLoadSuccess", snapshot.lastLoadSuccess ?: JSONObject.NULL)
            put("lastErrorMessage", snapshot.lastErrorMessage ?: JSONObject.NULL)
        }
        root.put("runtimeStatus", runtime)

        // Diagnostic Logs
        val logsArray = JSONArray()
        for (log in logs) {
            val logObj = JSONObject().apply {
                put("id", log.id)
                put("timestamp", log.formattedTimestamp)
                put("level", log.level.name)
                put("category", log.category.name)
                put("message", log.message)
                put("details", log.details ?: JSONObject.NULL)
            }
            logsArray.put(logObj)
        }
        root.put("recentDiagnosticLogs", logsArray)

        return root.toString(2)
    }

    fun exportToStream(
        snapshot: SystemDiagnosticSnapshot,
        logs: List<DiagnosticLogEntry>,
        outputStream: OutputStream
    ) {
        val json = generateJsonReport(snapshot, logs)
        outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(json)
            writer.flush()
        }
    }
}
