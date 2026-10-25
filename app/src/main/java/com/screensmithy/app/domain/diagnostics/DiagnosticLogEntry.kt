package com.screensmithy.app.domain.diagnostics

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Structured diagnostic log entry.
 */
data class DiagnosticLogEntry(
    val id: Long,
    val timestampEpochMs: Long,
    val level: LogLevel,
    val category: LogCategory,
    val message: String,
    val details: String? = null
) {
    val formattedTimestamp: String
        get() {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            return sdf.format(Date(timestampEpochMs))
        }

    val displayTimeShort: String
        get() {
            val sdf = SimpleDateFormat("HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getDefault()
            }
            return sdf.format(Date(timestampEpochMs))
        }
}

enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR
}

enum class LogCategory {
    STARTUP,
    CONFIG,
    IMPORT,
    RENDERER,
    AUTH,
    NETWORK,
    KIOSK_POLICY
}
