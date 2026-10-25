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
    data class InvalidExtension(val fileName: String, val allowedExtensions: Set<String>) : ImportError
    data class EmptyFile(val fileName: String) : ImportError
    data class FileTooLarge(val sizeBytes: Long, val maxAllowedBytes: Long) : ImportError
    data class ReadError(val message: String, val cause: Throwable? = null) : ImportError
    data class VerificationFailed(val reason: String) : ImportError
    data class StorageError(val message: String, val cause: Throwable? = null) : ImportError
}
