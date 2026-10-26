package com.screensmithy.app.data.transfer

import com.screensmithy.app.domain.transfer.TransferSessionState
import com.screensmithy.app.domain.transfer.WifiTransferSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.nio.charset.StandardCharsets

class WifiTransferHttpServerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")
    private var port: Int = 0
    private lateinit var stagingDir: File
    private var activeSession: WifiTransferSession? = null
    private var server: WifiTransferHttpServer? = null

    private var uploadCompletedFile: File? = null
    private var uploadCompletedName: String? = null
    private var failedPairingAttemptCount = 0

    @Before
    fun setUp() {
        stagingDir = tempFolder.newFolder("staging")
        // Find free port on loopback
        port = ServerSocket(0, 1, loopback).use { it.localPort }

        activeSession = WifiTransferSession(
            sessionId = "test-session",
            pairingCode = "123456",
            ipAddress = "127.0.0.1",
            port = port
        )

        uploadCompletedFile = null
        uploadCompletedName = null
        failedPairingAttemptCount = 0

        server = WifiTransferHttpServer(
            bindAddress = loopback,
            port = port,
            stagingDir = stagingDir,
            sessionProvider = { activeSession },
            onUploadCompleted = { fileName, stagedFile, _ ->
                uploadCompletedName = fileName
                uploadCompletedFile = stagedFile
            },
            onPairingAttemptFailed = {
                failedPairingAttemptCount++
            }
        ).apply {
            start()
        }
        Thread.sleep(100) // Ensure server started
    }

    @After
    fun tearDown() {
        server?.stop()
    }

    @Test
    fun getRoot_returnsHtmlSenderPage() {
        val url = URL("http://127.0.0.1:$port/")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 3000
            readTimeout = 3000
        }

        assertEquals(200, conn.responseCode)
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        assertTrue(body.contains("ScreenSmithy"))
        assertTrue(body.contains("25 MB"))
        assertTrue(body.contains("Send to TV"))
    }

    @Test
    fun getRoot_withDnsRebindingHost_returnsForbidden() {
        java.net.Socket("127.0.0.1", port).use { socket ->
            val request = "GET / HTTP/1.1\r\nHost: evil.attacker.com:8080\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().write(request.toByteArray(StandardCharsets.UTF_8))
            socket.getOutputStream().flush()

            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
            val statusLine = reader.readLine()
            assertNotNull(statusLine)
            assertTrue(statusLine.contains("403"))
        }
    }

    @Test
    fun postUpload_withoutPairingCode_returnsUnauthorized() {
        val boundary = "===Boundary==="
        val url = URL("http://127.0.0.1:$port/api/upload")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Host", "127.0.0.1:$port")
            connectTimeout = 3000
            readTimeout = 3000
        }

        val body = "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"index.html\"\r\n" +
                "Content-Type: text/html\r\n\r\n" +
                "<h1>Hello</h1>\r\n" +
                "--$boundary--\r\n"

        conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }

        assertEquals(401, conn.responseCode)
        assertEquals(1, failedPairingAttemptCount)
    }

    @Test
    fun postUpload_withIncorrectPairingCode_returnsUnauthorized() {
        val boundary = "===Boundary==="
        val url = URL("http://127.0.0.1:$port/api/upload")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Host", "127.0.0.1:$port")
            connectTimeout = 3000
            readTimeout = 3000
        }

        val body = "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"pairingCode\"\r\n\r\n" +
                "999999\r\n" +
                "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"index.html\"\r\n" +
                "Content-Type: text/html\r\n\r\n" +
                "<h1>Hello</h1>\r\n" +
                "--$boundary--\r\n"

        conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }

        assertEquals(401, conn.responseCode)
        assertEquals(1, failedPairingAttemptCount)
    }

    @Test
    fun postUpload_withValidPairingCodeAndHtml_succeeds() {
        val boundary = "===Boundary==="
        val url = URL("http://127.0.0.1:$port/api/upload")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Host", "127.0.0.1:$port")
            connectTimeout = 3000
            readTimeout = 3000
        }

        val html = "<html><body><h1>Kiosk Menu</h1></body></html>"
        val body = "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"pairingCode\"\r\n\r\n" +
                "123456\r\n" +
                "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"menu.html\"\r\n" +
                "Content-Type: text/html\r\n\r\n" +
                html + "\r\n" +
                "--$boundary--\r\n"

        conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }

        assertEquals(200, conn.responseCode)
        val responseText = conn.inputStream.bufferedReader().use { it.readText() }
        assertTrue(responseText.contains("\"success\":true"))

        assertNotNull(uploadCompletedFile)
        assertEquals("menu.html", uploadCompletedName)
        assertTrue(uploadCompletedFile!!.exists())
        assertEquals(html, uploadCompletedFile!!.readText())
    }

    @Test
    fun postUpload_withInvalidExtension_returnsBadRequest() {
        val boundary = "===Boundary==="
        val url = URL("http://127.0.0.1:$port/api/upload")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Host", "127.0.0.1:$port")
            connectTimeout = 3000
            readTimeout = 3000
        }

        val body = "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"pairingCode\"\r\n\r\n" +
                "123456\r\n" +
                "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"malicious.exe\"\r\n" +
                "Content-Type: application/octet-stream\r\n\r\n" +
                "binary payload\r\n" +
                "--$boundary--\r\n"

        conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }

        assertEquals(400, conn.responseCode)
        val errorText = conn.errorStream.bufferedReader().use { it.readText() }
        assertTrue(errorText.contains("Only .html and .htm"))
    }
}
