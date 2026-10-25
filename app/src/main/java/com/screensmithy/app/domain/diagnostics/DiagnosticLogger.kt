package com.screensmithy.app.domain.diagnostics

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * Interface for recording structured, bounded, sanitized diagnostic events.
 */
interface DiagnosticLogger {
    fun log(level: LogLevel, category: LogCategory, message: String, details: String? = null)
    fun info(category: LogCategory, message: String, details: String? = null)
    fun warn(category: LogCategory, message: String, details: String? = null)
    fun error(category: LogCategory, message: String, details: String? = null)
    fun debug(category: LogCategory, message: String, details: String? = null)
    fun getRecentLogs(): List<DiagnosticLogEntry>
    fun clear()
}

/**
 * Thread-safe, in-memory bounded diagnostic logger implementing FIFO rotation.
 * Automatically sanitizes all log messages and details to prevent secret leakage.
 */
class BoundedDiagnosticLogger(
    val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) : DiagnosticLogger {

    private val lock = Any()
    private val buffer = ArrayDeque<DiagnosticLogEntry>(maxEntries)
    private val idCounter = AtomicLong(1)

    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    override fun log(level: LogLevel, category: LogCategory, message: String, details: String?) {
        val sanitizedMessage = SecretSanitizer.sanitizeMessage(message)
        val sanitizedDetails = details?.let { SecretSanitizer.sanitizeMessage(it) }

        val entry = DiagnosticLogEntry(
            id = idCounter.getAndIncrement(),
            timestampEpochMs = timeProvider(),
            level = level,
            category = category,
            message = sanitizedMessage,
            details = sanitizedDetails
        )

        synchronized(lock) {
            while (buffer.size >= maxEntries) {
                buffer.removeFirst()
            }
            buffer.addLast(entry)
        }

        try {
            when (level) {
                LogLevel.DEBUG -> android.util.Log.d("ScreenSmithy", "[${category.name}] $sanitizedMessage")
                LogLevel.INFO -> android.util.Log.i("ScreenSmithy", "[${category.name}] $sanitizedMessage")
                LogLevel.WARN -> android.util.Log.w("ScreenSmithy", "[${category.name}] $sanitizedMessage")
                LogLevel.ERROR -> android.util.Log.e("ScreenSmithy", "[${category.name}] $sanitizedMessage")
            }
        } catch (_: Exception) {
            // Unit test environment fallback
        }
    }

    override fun info(category: LogCategory, message: String, details: String?) {
        log(LogLevel.INFO, category, message, details)
    }

    override fun warn(category: LogCategory, message: String, details: String?) {
        log(LogLevel.WARN, category, message, details)
    }

    override fun error(category: LogCategory, message: String, details: String?) {
        log(LogLevel.ERROR, category, message, details)
    }

    override fun debug(category: LogCategory, message: String, details: String?) {
        log(LogLevel.DEBUG, category, message, details)
    }

    override fun getRecentLogs(): List<DiagnosticLogEntry> {
        return synchronized(lock) {
            buffer.toList()
        }
    }

    override fun clear() {
        synchronized(lock) {
            buffer.clear()
        }
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 200
    }
}
