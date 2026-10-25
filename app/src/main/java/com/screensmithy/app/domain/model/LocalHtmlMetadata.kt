package com.screensmithy.app.domain.model

/**
 * Metadata referencing an imported HTML document in app-private storage.
 * Does not expose or store arbitrary device filesystem paths.
 */
data class LocalHtmlMetadata(
    val documentId: String,
    val displayName: String,
    val relativePath: String,
    val sizeBytes: Long,
    val importedAtEpochMs: Long,
    val sha256Checksum: String? = null
) {
    init {
        require(documentId.isNotBlank()) { "documentId must not be blank" }
        require(displayName.isNotBlank()) { "displayName must not be blank" }
        require(relativePath.isNotBlank()) { "relativePath must not be blank" }
        require(!relativePath.startsWith("/")) { "relativePath must not be an absolute path" }
        require(!relativePath.contains("..")) { "relativePath must not contain path traversal sequences" }
        require(sizeBytes in 1..MAX_IMPORT_SIZE_BYTES) {
            "sizeBytes ($sizeBytes) must be positive and not exceed $MAX_IMPORT_SIZE_BYTES bytes"
        }
    }

    companion object {
        /**
         * Maximum allowed single-file HTML import size (25 MB) as defined in MASTER_PROMPT.md.
         */
        const val MAX_IMPORT_SIZE_BYTES: Long = 25 * 1024 * 1024
    }
}
