package com.screensmithy.app.domain.importer

import com.screensmithy.app.domain.model.LocalHtmlMetadata
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

@OptIn(ExperimentalCoroutinesApi::class)
class LocalHtmlImporterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var mockResolver: FakeContentStreamResolver
    private lateinit var appFilesDir: File
    private lateinit var importer: LocalHtmlImporter

    @Before
    fun setUp() {
        appFilesDir = tempFolder.newFolder("app_files")
        mockResolver = FakeContentStreamResolver()
        importer = LocalHtmlImporter(
            appFilesDir = appFilesDir,
            streamResolver = mockResolver,
            ioDispatcher = testDispatcher
        )
    }

    @Test
    fun importDocument_whenUriNull_returnsCancelled() = testScope.runTest {
        val result = importer.importDocument(null)
        assertEquals(ImportResult.Cancelled, result)
    }

    @Test
    fun importDocument_whenUriBlank_returnsCancelled() = testScope.runTest {
        val result = importer.importDocument("   ")
        assertEquals(ImportResult.Cancelled, result)
    }

    @Test
    fun importDocument_withValidHtml_importsSuccessfullyAndHashes() = testScope.runTest {
        val htmlContent = "<html><body><h1>Kiosk Display</h1></body></html>"
        val bytes = htmlContent.toByteArray(Charsets.UTF_8)
        val uri = "content://com.android.providers.media/document/123"

        mockResolver.register(
            uri = uri,
            name = "kiosk_page.html",
            bytes = bytes
        )

        val result = importer.importDocument(uri)
        assertTrue(result is ImportResult.Success)

        val metadata = (result as ImportResult.Success).metadata
        assertEquals("kiosk_page.html", metadata.displayName)
        assertEquals(bytes.size.toLong(), metadata.sizeBytes)
        assertNotNull(metadata.sha256Checksum)

        // Verify SHA-256 computation
        val expectedHash = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
        assertEquals(expectedHash, metadata.sha256Checksum)

        // Verify file was written to app-private storage
        val importedFile = File(appFilesDir, metadata.relativePath)
        assertTrue(importedFile.exists())
        assertEquals(htmlContent, importedFile.readText(Charsets.UTF_8))

        // Verify staging directory is clean
        val stagingDir = File(appFilesDir, LocalHtmlImporter.STAGING_DIR_PATH)
        val stagingFiles = stagingDir.listFiles() ?: emptyArray()
        assertEquals(0, stagingFiles.size)
    }

    @Test
    fun importDocument_withValidHtmExtension_importsSuccessfully() = testScope.runTest {
        val content = "<html><body>Compact HTM</body></html>"
        val uri = "content://docs/htm_file"
        mockResolver.register(uri = uri, name = "display.htm", bytes = content.toByteArray())

        val result = importer.importDocument(uri)
        assertTrue(result is ImportResult.Success)
        val metadata = (result as ImportResult.Success).metadata
        assertEquals("display.htm", metadata.displayName)
    }

    @Test
    fun importDocument_withInvalidExtension_returnsInvalidExtensionFailure() = testScope.runTest {
        val uri = "content://docs/invoice.pdf"
        mockResolver.register(uri = uri, name = "invoice.pdf", bytes = byteArrayOf(1, 2, 3))

        val result = importer.importDocument(uri)
        assertTrue(result is ImportResult.Failure)
        val error = (result as ImportResult.Failure).error
        assertTrue(error is ImportError.InvalidExtension)
        assertEquals("invoice.pdf", (error as ImportError.InvalidExtension).fileName)
    }

    @Test
    fun importDocument_withEmptyFile_returnsEmptyFileFailureAndCleansStaging() = testScope.runTest {
        val uri = "content://docs/empty.html"
        mockResolver.register(uri = uri, name = "empty.html", bytes = ByteArray(0))

        val result = importer.importDocument(uri)
        assertTrue(result is ImportResult.Failure)
        val error = (result as ImportResult.Failure).error
        assertTrue(error is ImportError.EmptyFile)

        // Staging file must be cleaned up
        val stagingDir = File(appFilesDir, LocalHtmlImporter.STAGING_DIR_PATH)
        assertEquals(0, stagingDir.listFiles()?.size ?: 0)
    }

    @Test
    fun importDocument_whenQueriedSizeExceeds25Mb_failsFastWithoutReadingStream() = testScope.runTest {
        val uri = "content://docs/giant.html"
        val oversizedBytes = LocalHtmlMetadata.MAX_IMPORT_SIZE_BYTES + 1024L

        mockResolver.register(
            uri = uri,
            name = "giant.html",
            bytes = ByteArray(10), // stream body is small, but metadata declares > 25MB
            declaredSize = oversizedBytes
        )

        val result = importer.importDocument(uri)
        assertTrue(result is ImportResult.Failure)
        val error = (result as ImportResult.Failure).error
        assertTrue(error is ImportError.FileTooLarge)
        assertEquals(oversizedBytes, (error as ImportError.FileTooLarge).sizeBytes)
        assertFalse(mockResolver.wasStreamOpened(uri))
    }

    @Test
    fun importDocument_whenStreamExceeds25MbDuringRead_abortsAndCleansStaging() = testScope.runTest {
        val uri = "content://docs/stream_bomb.html"
        // Return null declared size to bypass fail-fast, but stream exceeds 25 MB
        val stream = object : InputStream() {
            var count = 0L
            val limit = LocalHtmlMetadata.MAX_IMPORT_SIZE_BYTES + 100

            override fun read(): Int {
                return if (count < limit) {
                    count++
                    'A'.code
                } else {
                    -1
                }
            }
        }

        mockResolver.registerStream(
            uri = uri,
            name = "stream_bomb.html",
            stream = stream,
            declaredSize = null
        )

        val result = importer.importDocument(uri)
        assertTrue(result is ImportResult.Failure)
        val error = (result as ImportResult.Failure).error
        assertTrue(error is ImportError.FileTooLarge)

        // Staging file must be safely deleted
        val stagingDir = File(appFilesDir, LocalHtmlImporter.STAGING_DIR_PATH)
        assertEquals(0, stagingDir.listFiles()?.size ?: 0)
    }

    @Test
    fun importDocument_whenStreamThrowsIOException_returnsReadErrorAndCleansStaging() = testScope.runTest {
        val uri = "content://docs/corrupt.html"
        val failingStream = object : InputStream() {
            var readFirst = false
            override fun read(): Int {
                if (!readFirst) {
                    readFirst = true
                    return 60 // '<'
                }
                throw IOException("Disk read failure / broken pipe")
            }
        }

        mockResolver.registerStream(uri = uri, name = "corrupt.html", stream = failingStream)

        val result = importer.importDocument(uri)
        assertTrue(result is ImportResult.Failure)
        val error = (result as ImportResult.Failure).error
        assertTrue(error is ImportError.ReadError)

        val stagingDir = File(appFilesDir, LocalHtmlImporter.STAGING_DIR_PATH)
        assertEquals(0, stagingDir.listFiles()?.size ?: 0)
    }

    @Test
    fun importDocument_whenFailed_preservesPriorImportedContent() = testScope.runTest {
        // Setup prior working file in storage
        val priorDir = File(appFilesDir, "imported_html/doc_prior_123").apply { mkdirs() }
        val priorFile = File(priorDir, "index.html")
        priorFile.writeText("<h1>Prior Active Kiosk Content</h1>")

        // Attempt invalid import (empty file)
        val uri = "content://docs/broken.html"
        mockResolver.register(uri = uri, name = "broken.html", bytes = ByteArray(0))

        val result = importer.importDocument(uri)
        assertTrue(result is ImportResult.Failure)

        // Prior file must remain completely intact
        assertTrue(priorFile.exists())
        assertEquals("<h1>Prior Active Kiosk Content</h1>", priorFile.readText())
    }

    @Test
    fun importDocument_sanitizesFileNamesWithSpecialCharactersOrTraversal() = testScope.runTest {
        val uri = "content://docs/traversal.html"
        val content = "<html><body>Safe</body></html>"
        mockResolver.register(
            uri = uri,
            name = "../../etc/passwd?foo=bar#section.html",
            bytes = content.toByteArray()
        )

        val result = importer.importDocument(uri)
        assertTrue(result is ImportResult.Success)

        val metadata = (result as ImportResult.Success).metadata
        assertFalse(metadata.relativePath.contains(".."))
        assertFalse(metadata.relativePath.contains("?"))
        assertFalse(metadata.relativePath.contains("#"))

        val targetFile = File(appFilesDir, metadata.relativePath)
        assertTrue(targetFile.exists())
        assertEquals(content, targetFile.readText())
    }

    @Test
    fun cleanupStagingFiles_deletesOnlyOldTemporaryFiles() = testScope.runTest {
        val stagingDir = File(appFilesDir, LocalHtmlImporter.STAGING_DIR_PATH).apply { mkdirs() }

        val oldTmp = File(stagingDir, "import_old.tmp").apply {
            createNewFile()
            setLastModified(System.currentTimeMillis() - 7200_000L) // 2 hours old
        }

        val recentTmp = File(stagingDir, "import_recent.tmp").apply {
            createNewFile()
            setLastModified(System.currentTimeMillis())
        }

        val nonTmpFile = File(stagingDir, "keep_me.txt").apply {
            createNewFile()
            setLastModified(System.currentTimeMillis() - 7200_000L)
        }

        val deletedCount = importer.cleanupStagingFiles(maxAgeMs = 3600_000L) // 1 hour max age
        assertEquals(1, deletedCount)

        assertFalse(oldTmp.exists())
        assertTrue(recentTmp.exists())
        assertTrue(nonTmpFile.exists())
    }

    @Test
    fun importStagedFile_withValidHtml_importsSuccessfullyAndHashes() = testScope.runTest {
        val htmlContent = "<html><body><h1>Wi-Fi Imported</h1></body></html>"
        val stagedFile = tempFolder.newFile("wifi_staged.tmp").apply {
            writeText(htmlContent)
        }

        val result = importer.importStagedFile(stagedFile, "signage.html")
        assertTrue(result is ImportResult.Success)

        val metadata = (result as ImportResult.Success).metadata
        assertEquals("signage.html", metadata.displayName)
        assertEquals(htmlContent.toByteArray().size.toLong(), metadata.sizeBytes)
        assertFalse(stagedFile.exists()) // Promoted/moved

        val storedFile = File(appFilesDir, metadata.relativePath)
        assertTrue(storedFile.exists())
        assertEquals(htmlContent, storedFile.readText())
    }

    @Test
    fun importStagedFile_withInvalidExtension_returnsFailure() = testScope.runTest {
        val stagedFile = tempFolder.newFile("wifi_staged.tmp").apply {
            writeText("executable code")
        }

        val result = importer.importStagedFile(stagedFile, "evil.exe")
        assertTrue(result is ImportResult.Failure)
        assertTrue((result as ImportResult.Failure).error is ImportError.InvalidExtension)
        assertFalse(stagedFile.exists()) // Cleaned up
    }

    @Test
    fun importStagedFile_withEmptyFile_returnsFailure() = testScope.runTest {
        val stagedFile = tempFolder.newFile("empty.tmp")

        val result = importer.importStagedFile(stagedFile, "empty.html")
        assertTrue(result is ImportResult.Failure)
        assertTrue((result as ImportResult.Failure).error is ImportError.EmptyFile)
    }

    @Test
    fun importStagedFile_sanitizesPathTraversal_storesSafely() = testScope.runTest {
        val htmlContent = "<p>Path traversal test</p>"
        val stagedFile = tempFolder.newFile("staged_traversal.tmp").apply {
            writeText(htmlContent)
        }

        val result = importer.importStagedFile(stagedFile, "../../../etc/passwd.html")
        assertTrue(result is ImportResult.Success)

        val metadata = (result as ImportResult.Success).metadata
        assertFalse(metadata.relativePath.contains(".."))
        assertTrue(metadata.relativePath.startsWith("imported_html/"))
    }

    /**
     * Test fake implementation of [ContentStreamResolver].
     */
    private class FakeContentStreamResolver : ContentStreamResolver {
        private val descriptors = mutableMapOf<String, DocumentDescriptor>()
        private val streams = mutableMapOf<String, () -> InputStream>()
        private val openedStreams = mutableSetOf<String>()

        fun register(uri: String, name: String, bytes: ByteArray, declaredSize: Long? = bytes.size.toLong()) {
            descriptors[uri] = DocumentDescriptor(displayName = name, sizeBytes = declaredSize)
            streams[uri] = { ByteArrayInputStream(bytes) }
        }

        fun registerStream(uri: String, name: String, stream: InputStream, declaredSize: Long? = null) {
            descriptors[uri] = DocumentDescriptor(displayName = name, sizeBytes = declaredSize)
            streams[uri] = { stream }
        }

        fun wasStreamOpened(uri: String): Boolean = openedStreams.contains(uri)

        override fun queryDescriptor(uriString: String): DocumentDescriptor? {
            return descriptors[uriString]
        }

        override fun openInputStream(uriString: String): InputStream? {
            openedStreams.add(uriString)
            return streams[uriString]?.invoke()
        }
    }
}
