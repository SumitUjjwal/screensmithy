package com.screensmithy.app.domain.importer

import com.screensmithy.app.domain.model.LocalHtmlMetadata
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/**
 * Service for safely validating, importing, and hashing a single local HTML document
 * into app-private storage.
 *
 * ### Storage Layout
 * ```
 * <app-private-files-dir>/
 *   imported_html/
 *     staging/
 *       import_<uuid>.tmp
 *     <documentId>/
 *       <sanitizedFileName>.html
 * ```
 * The returned [LocalHtmlMetadata.relativePath] is relative to the app-private storage root,
 * e.g., `imported_html/doc_1700000000_a1b2c3d4/index.html`. This ensures the path is safely
 * usable by the future WebView content loader without exposing arbitrary device filesystems.
 *
 * ### Scope Limitation
 * This service supports **self-contained single-file HTML only** (inline styles/scripts and base64
 * embedded assets). Sibling local filesystem assets (e.g. `<img src="sub/photo.jpg">`) are not
 * bundled or resolved in single-file mode; directory/bundle import is not part of this component.
 *
 * @param appFilesDir The root app-private files directory (e.g. [android.content.Context.getFilesDir]).
 * @param streamResolver Provider for content metadata and input streams.
 * @param ioDispatcher Dispatcher for asynchronous off-main-thread I/O.
 */
class LocalHtmlImporter(
    private val appFilesDir: File,
    private val streamResolver: ContentStreamResolver,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    /**
     * Imports an HTML document referenced by [uriString] into app-private storage.
     *
     * @param uriString URI string provided by the SAF picker, or null if the user cancelled.
     * @return [ImportResult.Cancelled], [ImportResult.Success], or [ImportResult.Failure].
     */
    suspend fun importDocument(uriString: String?): ImportResult = withContext(ioDispatcher) {
        if (uriString.isNullOrBlank()) {
            return@withContext ImportResult.Cancelled
        }

        val descriptor = streamResolver.queryDescriptor(uriString)
        val rawFileName = descriptor?.displayName ?: "imported_content.html"

        // 1. Validate file extension
        val sanitizedExtension = getSanitizedExtension(rawFileName)
        if (sanitizedExtension !in ALLOWED_EXTENSIONS) {
            return@withContext ImportResult.Failure(
                ImportError.InvalidExtension(rawFileName, ALLOWED_EXTENSIONS)
            )
        }

        // 2. Fail-fast on pre-queried size limit if available
        val queriedSize = descriptor?.sizeBytes
        if (queriedSize != null && queriedSize > LocalHtmlMetadata.MAX_IMPORT_SIZE_BYTES) {
            return@withContext ImportResult.Failure(
                ImportError.FileTooLarge(queriedSize, LocalHtmlMetadata.MAX_IMPORT_SIZE_BYTES)
            )
        }

        val stagingDir = File(appFilesDir, STAGING_DIR_PATH).apply { mkdirs() }
        val stagingFile = File(stagingDir, "import_${UUID.randomUUID()}.tmp")

        try {
            // 3. Open input stream and copy into staging file with on-the-fly digest and size enforcement
            val inputStream = streamResolver.openInputStream(uriString)
                ?: return@withContext ImportResult.Failure(
                    ImportError.ReadError("Unable to open input stream for URI: $uriString")
                )

            val digest = MessageDigest.getInstance("SHA-256")
            var totalBytesRead = 0L

            inputStream.use { input ->
                FileOutputStream(stagingFile).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        totalBytesRead += bytesRead

                        if (totalBytesRead > LocalHtmlMetadata.MAX_IMPORT_SIZE_BYTES) {
                            throw FileTooLargeException(
                                totalBytesRead,
                                LocalHtmlMetadata.MAX_IMPORT_SIZE_BYTES
                            )
                        }

                        digest.update(buffer, 0, bytesRead)
                        output.write(buffer, 0, bytesRead)
                    }
                    output.flush()
                }
            }

            // 4. Verify content length
            if (totalBytesRead == 0L) {
                safeDelete(stagingFile)
                return@withContext ImportResult.Failure(ImportError.EmptyFile(rawFileName))
            }

            if (!stagingFile.exists() || stagingFile.length() != totalBytesRead) {
                safeDelete(stagingFile)
                return@withContext ImportResult.Failure(
                    ImportError.VerificationFailed("Staged file size (${stagingFile.length()}) does not match bytes read ($totalBytesRead)")
                )
            }

            // 5. Prepare final destination and promote atomically
            val documentId = generateDocumentId()
            val sanitizedFileName = sanitizeFileName(rawFileName)
            val documentDir = File(File(appFilesDir, ROOT_CONTENT_DIR), documentId).apply { mkdirs() }
            val destinationFile = File(documentDir, sanitizedFileName)

            promoteFile(stagingFile, destinationFile)

            val checksum = digest.digest().joinToString("") { "%02x".format(it) }
            val relativePath = "$ROOT_CONTENT_DIR/$documentId/$sanitizedFileName"

            val metadata = LocalHtmlMetadata(
                documentId = documentId,
                displayName = rawFileName,
                relativePath = relativePath,
                sizeBytes = totalBytesRead,
                importedAtEpochMs = System.currentTimeMillis(),
                sha256Checksum = checksum
            )

            ImportResult.Success(metadata)

        } catch (e: FileTooLargeException) {
            safeDelete(stagingFile)
            ImportResult.Failure(ImportError.FileTooLarge(e.sizeBytes, e.maxAllowedBytes))
        } catch (e: IOException) {
            safeDelete(stagingFile)
            ImportResult.Failure(ImportError.ReadError(e.message ?: "I/O error during file copy", e))
        } catch (e: Exception) {
            safeDelete(stagingFile)
            ImportResult.Failure(ImportError.StorageError(e.message ?: "Unexpected error during import", e))
        }
    }

    /**
     * Cleans up orphaned staging files older than [maxAgeMs].
     * Never deletes files outside the dedicated staging directory.
     */
    suspend fun cleanupStagingFiles(maxAgeMs: Long = STAGING_FILE_MAX_AGE_MS): Int = withContext(ioDispatcher) {
        val stagingDir = File(appFilesDir, STAGING_DIR_PATH)
        if (!stagingDir.exists() || !stagingDir.isDirectory) return@withContext 0

        val now = System.currentTimeMillis()
        var deletedCount = 0

        stagingDir.listFiles()?.forEach { file ->
            if (file.isFile && file.name.endsWith(".tmp") && (now - file.lastModified() > maxAgeMs)) {
                if (file.delete()) {
                    deletedCount++
                }
            }
        }

        deletedCount
    }

    private fun getSanitizedExtension(fileName: String): String {
        val lastDotIndex = fileName.lastIndexOf('.')
        return if (lastDotIndex != -1 && lastDotIndex < fileName.length - 1) {
            fileName.substring(lastDotIndex + 1).lowercase()
        } else {
            ""
        }
    }

    private fun sanitizeFileName(fileName: String): String {
        // Strip path traversal sequences and keep alphanumeric, underscores, hyphens, and extension
        val baseName = fileName.substringAfterLast('/').substringAfterLast('\\')
        val sanitized = baseName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return sanitized.ifBlank { "index.html" }
    }

    private fun generateDocumentId(): String {
        val timestamp = System.currentTimeMillis()
        val randomSuffix = UUID.randomUUID().toString().take(8)
        return "doc_${timestamp}_$randomSuffix"
    }

    private fun promoteFile(stagingFile: File, destinationFile: File) {
        if (destinationFile.exists()) {
            destinationFile.delete()
        }

        val renamed = stagingFile.renameTo(destinationFile)
        if (!renamed) {
            // Fallback for cross-partition moves: stream copy followed by staging cleanup
            stagingFile.inputStream().use { input ->
                destinationFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            safeDelete(stagingFile)
        }
    }

    private fun safeDelete(file: File) {
        try {
            if (file.exists()) {
                file.delete()
            }
        } catch (_: Exception) {
            // Best effort cleanup
        }
    }

    private class FileTooLargeException(val sizeBytes: Long, val maxAllowedBytes: Long) : IOException()

    companion object {
        const val ROOT_CONTENT_DIR = "imported_html"
        const val STAGING_DIR_PATH = "imported_html/staging"
        const val BUFFER_SIZE = 8192
        const val STAGING_FILE_MAX_AGE_MS = 60 * 60 * 1000L // 1 hour

        val ALLOWED_EXTENSIONS = setOf("html", "htm")
    }
}
