package com.screensmithy.app.data.transfer

import com.screensmithy.app.domain.transfer.WifiTransferSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.SupervisorJob
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID

/**
 * Lightweight, purpose-built HTTP/1.1 server for temporary local Wi-Fi HTML file transfers.
 *
 * Security Characteristics:
 * - Binds exclusively to the specified local network [bindAddress] (never 0.0.0.0).
 * - Constant-time pairing code comparison using [MessageDigest.isEqual].
 * - Bounded request size (50 MB) streamed directly to disk, avoiding memory exhaustion.
 * - Host header validation to mitigate DNS rebinding.
 * - Sanitized filename handling to prevent directory traversal.
 * - Fail-closed: shuts down immediately upon session completion, timeout, or cancellation.
 */
class WifiTransferHttpServer(
    val bindAddress: InetAddress,
    val port: Int,
    private val stagingDir: File,
    private val sessionProvider: () -> WifiTransferSession?,
    private val onUploadProgress: (fileName: String, bytesRead: Long, totalBytes: Long) -> Unit = { _, _, _ -> },
    private val onUploadCompleted: (fileName: String, stagedFile: File, sizeBytes: Long) -> Unit = { _, _, _ -> },
    private val onUploadFailed: (reason: String) -> Unit = {},
    private val onPairingAttemptFailed: () -> Unit = {},
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    val isRunning: Boolean get() = serverSocket?.isClosed == false && serverJob?.isActive == true

    /**
     * Starts listening for client connections on the bound local interface and port.
     */
    fun start() {
        if (isRunning) return

        stagingDir.mkdirs()
        val socket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(bindAddress, port), 10)
        }
        serverSocket = socket

        serverJob = scope.launch {
            while (isActive && !socket.isClosed) {
                try {
                    val clientSocket = socket.accept()
                    launch {
                        handleClient(clientSocket)
                    }
                } catch (_: IOException) {
                    break
                }
            }
        }
    }

    /**
     * Stops the HTTP server and safely closes all network resources.
     */
    fun stop() {
        try {
            serverSocket?.close()
        } catch (_: IOException) {}
        serverSocket = null
        serverJob?.cancel()
        serverJob = null
    }

    private fun handleClient(socket: Socket) {
        socket.soTimeout = 15_000 // 15-second socket timeout
        try {
            socket.use { s ->
                val input = BufferedInputStream(s.getInputStream())
                val output = BufferedOutputStream(s.getOutputStream())

                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) {
                    sendResponse(output, 400, "Bad Request", "text/plain", "Malformed request line")
                    return
                }

                val method = parts[0].uppercase()
                val rawPath = parts[1]
                val path = rawPath.substringBefore("?")

                // Parse HTTP headers
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val colonIdx = line.indexOf(':')
                    if (colonIdx != -1) {
                        val key = line.substring(0, colonIdx).trim().lowercase()
                        val value = line.substring(colonIdx + 1).trim()
                        headers[key] = value
                    }
                }

                // DNS Rebinding Protection: Verify Host header matches the bound IP / port
                val hostHeader = headers["host"]?.substringBefore(":")
                if (hostHeader != null && hostHeader != bindAddress.hostAddress && hostHeader != "localhost" && hostHeader != "127.0.0.1") {
                    sendResponse(output, 403, "Forbidden", "application/json", """{"error":"Host header rejected"}""")
                    return
                }

                val currentSession = sessionProvider()
                if (currentSession == null || currentSession.isExpired || currentSession.isLockedOut) {
                    sendResponse(output, 410, "Gone", "application/json", """{"error":"Transfer session inactive or expired"}""")
                    return
                }

                when {
                    method == "GET" && (path == "/" || path == "/index.html") -> {
                        val initialCode = if (rawPath.contains("code=")) {
                            rawPath.substringAfter("code=").substringBefore("&").take(6)
                        } else {
                            ""
                        }
                        val html = SenderHtmlTemplate.render(initialCode)
                        sendResponse(output, 200, "OK", "text/html; charset=utf-8", html)
                    }

                    method == "GET" && path == "/api/session" -> {
                        val json = """{"active":true,"remainingSeconds":${currentSession.remainingSeconds}}"""
                        sendResponse(output, 200, "OK", "application/json", json)
                    }

                    method == "POST" && path == "/api/upload" -> {
                        handleUpload(input, output, headers, currentSession)
                    }

                    else -> {
                        sendResponse(output, 404, "Not Found", "text/plain", "Endpoint not found")
                    }
                }
            }
        } catch (_: Exception) {
            // Client disconnect or socket reset handled cleanly
        }
    }

    private fun handleUpload(
        input: InputStream,
        output: OutputStream,
        headers: Map<String, String>,
        session: WifiTransferSession
    ) {
        val contentLength = headers["content-length"]?.toLongOrNull() ?: -1L
        if (contentLength > MAX_UPLOAD_SIZE_BYTES) {
            sendResponse(output, 413, "Payload Too Large", "application/json", """{"success":false,"error":"File exceeds maximum 50 MB limit"}""")
            return
        }

        val contentType = headers["content-type"] ?: ""
        val boundary = extractBoundary(contentType)

        // Pairing code verification via header
        val headerPairingCode = headers["x-pairing-code"]?.trim()

        val tempFile = File(stagingDir, "transfer_${UUID.randomUUID()}.tmp")
        var declaredFileName = "uploaded_content.html"

        try {
            FileOutputStream(tempFile).use { fileOut ->
                val buffer = ByteArray(8192)
                var totalBytesRead = 0L

                if (boundary != null) {
                    // Streamed multipart parsing
                    val multipartReader = MultipartStreamHandler(input, boundary, contentLength)
                    var partPairingCode: String? = null

                    while (multipartReader.hasNextPart()) {
                        val partHeaders = multipartReader.readPartHeaders()
                        val contentDisposition = partHeaders["content-disposition"] ?: ""

                        if (contentDisposition.contains("name=\"pairingCode\"")) {
                            partPairingCode = multipartReader.readPartString().trim()
                        } else if (contentDisposition.contains("filename=")) {
                            declaredFileName = extractFilename(contentDisposition)
                            // Stream file body directly into temporary file
                            multipartReader.copyPartData(fileOut) { bytesRead, total ->
                                totalBytesRead = bytesRead
                                if (totalBytesRead > MAX_UPLOAD_SIZE_BYTES) {
                                    throw IOException("File exceeds 50 MB limit")
                                }
                                onUploadProgress(declaredFileName, bytesRead, total)
                            }
                        } else {
                            multipartReader.skipPartData()
                        }
                    }

                    // Authenticate code
                    val candidateCode = headerPairingCode ?: partPairingCode
                    if (!isCodeValid(candidateCode, session.pairingCode)) {
                        onPairingAttemptFailed()
                        safeDelete(tempFile)
                        sendResponse(output, 401, "Unauthorized", "application/json", """{"success":false,"error":"Invalid pairing code"}""")
                        return
                    }

                } else {
                    // Direct stream upload with header authentication
                    if (!isCodeValid(headerPairingCode, session.pairingCode)) {
                        onPairingAttemptFailed()
                        safeDelete(tempFile)
                        sendResponse(output, 401, "Unauthorized", "application/json", """{"success":false,"error":"Missing or invalid X-Pairing-Code header"}""")
                        return
                    }

                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        totalBytesRead += read
                        if (totalBytesRead > MAX_UPLOAD_SIZE_BYTES) {
                            throw IOException("File exceeds 50 MB limit")
                        }
                        fileOut.write(buffer, 0, read)
                        onUploadProgress(declaredFileName, totalBytesRead, contentLength)
                        if (contentLength > 0 && totalBytesRead >= contentLength) break
                    }
                }
                fileOut.flush()
            }

            // Sanitize filename
            val sanitizedName = sanitizeFilename(declaredFileName)
            val extension = sanitizedName.substringAfterLast('.', "").lowercase()
            if (extension !in listOf("html", "htm")) {
                safeDelete(tempFile)
                val msg = "Only .html and .htm documents are supported"
                onUploadFailed(msg)
                sendResponse(output, 400, "Bad Request", "application/json", """{"success":false,"error":"$msg"}""")
                return
            }

            if (tempFile.length() == 0L) {
                safeDelete(tempFile)
                val msg = "Uploaded file was empty"
                onUploadFailed(msg)
                sendResponse(output, 400, "Bad Request", "application/json", """{"success":false,"error":"$msg"}""")
                return
            }

            // HTML content verification (scan first 8KB for standard HTML markers)
            if (!verifyHtmlContent(tempFile)) {
                safeDelete(tempFile)
                val msg = "File does not appear to be a valid HTML document"
                onUploadFailed(msg)
                sendResponse(output, 400, "Bad Request", "application/json", """{"success":false,"error":"$msg"}""")
                return
            }

            // Notify successful upload
            val fileSize = tempFile.length()
            onUploadCompleted(sanitizedName, tempFile, fileSize)

            val json = """{"success":true,"fileName":"$sanitizedName","sizeBytes":$fileSize}"""
            sendResponse(output, 200, "OK", "application/json", json)

        } catch (e: Exception) {
            safeDelete(tempFile)
            val errorMsg = e.message ?: "Upload processing failed"
            onUploadFailed(errorMsg)
            sendResponse(output, 400, "Bad Request", "application/json", """{"success":false,"error":"$errorMsg"}""")
        }
    }

    private fun isCodeValid(candidate: String?, expected: String): Boolean {
        if (candidate.isNullOrBlank()) return false
        val candidateBytes = candidate.toByteArray(Charsets.UTF_8)
        val expectedBytes = expected.toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(candidateBytes, expectedBytes)
    }

    private fun sanitizeFilename(raw: String): String {
        return raw.replace('\\', '/')
            .substringAfterLast('/')
            .replace("..", "")
            .filter { it.isLetterOrDigit() || it in "._- " }
            .ifBlank { "imported_content.html" }
    }

    private fun verifyHtmlContent(file: File): Boolean {
        val buffer = ByteArray(8192)
        val bytesRead = file.inputStream().use { it.read(buffer) }
        if (bytesRead <= 0) return false
        val header = String(buffer, 0, bytesRead, Charsets.UTF_8).lowercase()
        return header.contains("<html") ||
                header.contains("<!doctype html") ||
                header.contains("<head") ||
                header.contains("<body")
    }

    private fun extractBoundary(contentType: String): String? {
        val parts = contentType.split(";")
        for (part in parts) {
            val trimmed = part.trim()
            if (trimmed.startsWith("boundary=", ignoreCase = true)) {
                return trimmed.substringAfter("=").trim('"')
            }
        }
        return null
    }

    private fun extractFilename(contentDisposition: String): String {
        val parts = contentDisposition.split(";")
        for (part in parts) {
            val trimmed = part.trim()
            if (trimmed.startsWith("filename=", ignoreCase = true)) {
                return trimmed.substringAfter("=").trim('"')
            }
        }
        return "uploaded_content.html"
    }

    private fun sendResponse(
        output: OutputStream,
        statusCode: Int,
        statusText: String,
        contentType: String,
        body: String
    ) {
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val response = buildString {
            append("HTTP/1.1 $statusCode $statusText\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bodyBytes.size}\r\n")
            append("Connection: close\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("\r\n")
        }
        output.write(response.toByteArray(Charsets.UTF_8))
        output.write(bodyBytes)
        output.flush()
    }

    private fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        var prev = -1
        while (true) {
            val b = input.read()
            if (b == -1) {
                return if (out.size() == 0) null else out.toString(Charsets.UTF_8.name())
            }
            if (b == '\n'.code && prev == '\r'.code) {
                val bytes = out.toByteArray()
                return String(bytes, 0, bytes.size - 1, Charsets.UTF_8)
            }
            out.write(b)
            prev = b
        }
    }

    private fun safeDelete(file: File) {
        try {
            if (file.exists()) file.delete()
        } catch (_: Exception) {}
    }

    companion object {
        const val MAX_UPLOAD_SIZE_BYTES: Long = com.screensmithy.app.domain.model.LocalHtmlMetadata.MAX_IMPORT_SIZE_BYTES // 25 MB
    }
}
