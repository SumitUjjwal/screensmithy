package com.screensmithy.app.domain.importer

import java.io.InputStream

/**
 * Descriptor containing metadata queried from a picked content URI.
 */
data class DocumentDescriptor(
    val displayName: String?,
    val sizeBytes: Long?
)

/**
 * Abstraction for querying metadata and opening input streams from content identifiers.
 * Decouples import business logic from Android framework ContentResolver for high-speed unit testing.
 */
interface ContentStreamResolver {
    /**
     * Queries display name and size metadata for the given content identifier.
     */
    fun queryDescriptor(uriString: String): DocumentDescriptor?

    /**
     * Opens an input stream for reading content from the given content identifier.
     */
    fun openInputStream(uriString: String): InputStream?
}
