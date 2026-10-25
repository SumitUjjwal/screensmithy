package com.screensmithy.app.domain.importer

import com.screensmithy.app.domain.model.LocalHtmlMetadata

/**
 * Result of a local HTML import operation.
 */
sealed interface ImportResult {
    /**
     * File was successfully imported, verified, and promoted to app-private storage.
     */
    data class Success(
        val metadata: LocalHtmlMetadata
    ) : ImportResult

    /**
     * User cancelled the system document picker.
     */
    data object Cancelled : ImportResult

    /**
     * Import failed due to validation, size limit, or I/O error.
     */
    data class Failure(
        val error: ImportError
    ) : ImportResult
}

/**
 * Detailed error causes for import failures.
 */
sealed interface ImportError {
    val userMessage: String

    data class InvalidExtension(val fileName: String, val allowedExtensions: Set<String>) : ImportError {
        override val userMessage: String = "Invalid file extension for '$fileName'. Allowed: ${allowedExtensions.joinToString(", ")}"
    }

    data class EmptyFile(val fileName: String) : ImportError {
        override val userMessage: String = "The selected file '$fileName' is empty."
    }

    data class FileTooLarge(val sizeBytes: Long, val maxAllowedBytes: Long) : ImportError {
        override val userMessage: String = "File exceeds maximum 25 MB limit (${sizeBytes / (1024 * 1024)} MB)."
    }

    data class ReadError(val message: String, val cause: Throwable? = null) : ImportError {
        override val userMessage: String = "Failed to read file: $message"
    }

    data class VerificationFailed(val reason: String) : ImportError {
        override val userMessage: String = "File verification failed: $reason"
    }

    data class StorageError(val message: String, val cause: Throwable? = null) : ImportError {
        override val userMessage: String = "Storage error: $message"
    }
}
