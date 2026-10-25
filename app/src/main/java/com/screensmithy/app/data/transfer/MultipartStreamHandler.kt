package com.screensmithy.app.data.transfer

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Streamed, low-memory multipart/form-data parser for the local upload endpoint.
 * Directly writes file data from the network socket into an [OutputStream] using
 * a fixed buffer, ensuring 25 MB files are never fully loaded into Android heap memory.
 */
class MultipartStreamHandler(
    private val input: InputStream,
    private val boundary: String,
    private val totalLength: Long
) {

    private val delimiterBytes = ("\r\n--$boundary").toByteArray(Charsets.ISO_8859_1)
    private var isFirstPart = true
    private var isEndReached = false
    private var totalBytesStreamed = 0L

    fun hasNextPart(): Boolean {
        if (isEndReached) return false

        if (isFirstPart) {
            // Read until the initial boundary line: --$boundary
            while (true) {
                val lineBytes = readLineBytes() ?: run {
                    isEndReached = true
                    return false
                }
                val line = String(lineBytes, Charsets.ISO_8859_1).trim()
                if (line.startsWith("--$boundary")) {
                    if (line.endsWith("--")) {
                        isEndReached = true
                        return false
                    }
                    isFirstPart = false
                    return true
                }
            }
        }

        // For subsequent parts, previous part's copyPartData consumed the boundary line
        return !isEndReached
    }

    fun readPartHeaders(): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        while (true) {
            val lineBytes = readLineBytes() ?: break
            if (lineBytes.isEmpty()) break
            val line = String(lineBytes, Charsets.UTF_8).trim()
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon != -1) {
                headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
        }
        return headers
    }

    fun readPartString(): String {
        val out = ByteArrayOutputStream()
        copyPartData(out)
        return out.toString(Charsets.UTF_8.name()).trim()
    }

    fun skipPartData() {
        val discard = object : OutputStream() {
            override fun write(b: Int) {}
            override fun write(b: ByteArray, off: Int, len: Int) {}
        }
        copyPartData(discard)
    }

    fun copyPartData(
        output: OutputStream,
        onProgress: (bytesRead: Long, total: Long) -> Unit = { _, _ -> }
    ) {
        val buffer = ByteArray(8192)
        var bufferPos = 0
        var matchPos = 0

        while (true) {
            val b = input.read()
            if (b == -1) {
                isEndReached = true
                break
            }

            if (b.toByte() == delimiterBytes[matchPos]) {
                matchPos++
                if (matchPos == delimiterBytes.size) {
                    // Reached boundary! Flush buffer before boundary prefix and check suffix
                    if (bufferPos > 0) {
                        output.write(buffer, 0, bufferPos)
                        totalBytesStreamed += bufferPos
                    }
                    val suffixBytes = readLineBytes()
                    val suffixStr = if (suffixBytes != null) String(suffixBytes, Charsets.ISO_8859_1) else ""
                    if (suffixStr.contains("--")) {
                        isEndReached = true
                    }
                    break
                }
            } else {
                if (matchPos > 0) {
                    // Flush partially matched delimiter bytes to output
                    for (i in 0 until matchPos) {
                        buffer[bufferPos++] = delimiterBytes[i]
                        if (bufferPos == buffer.size) {
                            output.write(buffer, 0, bufferPos)
                            totalBytesStreamed += bufferPos
                            onProgress(totalBytesStreamed, totalLength)
                            bufferPos = 0
                        }
                    }
                    matchPos = 0
                    // Retry current byte against delimiter start
                    if (b.toByte() == delimiterBytes[0]) {
                        matchPos = 1
                        continue
                    }
                }

                buffer[bufferPos++] = b.toByte()
                if (bufferPos == buffer.size) {
                    output.write(buffer, 0, bufferPos)
                    totalBytesStreamed += bufferPos
                    onProgress(totalBytesStreamed, totalLength)
                    bufferPos = 0
                }
            }
        }

        if (bufferPos > 0 && matchPos == 0) {
            output.write(buffer, 0, bufferPos)
            totalBytesStreamed += bufferPos
            onProgress(totalBytesStreamed, totalLength)
        }
        output.flush()
    }

    private fun readLineBytes(): ByteArray? {
        val out = ByteArrayOutputStream()
        var prev = -1
        while (true) {
            val b = input.read()
            if (b == -1) {
                return if (out.size() == 0) null else out.toByteArray()
            }
            if (b == '\n'.code && prev == '\r'.code) {
                val bytes = out.toByteArray()
                return bytes.copyOf(bytes.size - 1)
            }
            out.write(b)
            prev = b
        }
    }
}
