package com.screensmithy.app.domain.transfer

import com.screensmithy.app.data.transfer.NetworkUtils
import com.screensmithy.app.data.transfer.WifiTransferHttpServer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Coordinates temporary Wi-Fi upload sessions on the TV, managing the HTTP upload server,
 * session countdown timer, rate-limiting lockout, and staged file handoff.
 */
class WifiTransferManager(
    private val stagingDir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private val scope = CoroutineScope(ioDispatcher)
    private var activeServer: WifiTransferHttpServer? = null
    private var tickerJob: Job? = null

    private val _sessionState = MutableStateFlow<WifiTransferSession?>(null)
    val sessionState: StateFlow<WifiTransferSession?> = _sessionState.asStateFlow()

    /**
     * Initializes and starts a new temporary Wi-Fi transfer session on the local network interface.
     * Returns true if the session started successfully, or false if the TV is offline.
     */
    fun startSession(): Boolean {
        stopSession()

        val bindAddress = NetworkUtils.getLocalIpAddress() ?: run {
            _sessionState.value = WifiTransferSession(
                ipAddress = "127.0.0.1",
                port = 0,
                state = TransferSessionState.Failed("TV is offline. Please connect to a Wi-Fi or Ethernet network.")
            )
            return false
        }

        val port = NetworkUtils.findAvailablePort(bindAddress)
        val session = WifiTransferSession(
            ipAddress = bindAddress.hostAddress ?: "127.0.0.1",
            port = port
        )
        _sessionState.value = session

        val server = WifiTransferHttpServer(
            bindAddress = bindAddress,
            port = port,
            stagingDir = stagingDir,
            sessionProvider = { _sessionState.value },
            onUploadProgress = { fileName, bytesRead, totalBytes ->
                val percent = if (totalBytes > 0) ((bytesRead * 100) / totalBytes).toInt().coerceIn(0, 100) else 0
                updateSessionState {
                    it.copy(state = TransferSessionState.ReceivingUpload(fileName, bytesRead, totalBytes, percent))
                }
            },
            onUploadCompleted = { fileName, stagedFile, sizeBytes ->
                // Stop server immediately to prevent further uploads
                activeServer?.stop()
                activeServer = null
                tickerJob?.cancel()

                updateSessionState {
                    it.copy(state = TransferSessionState.Completed(fileName, sizeBytes, stagedFile))
                }
            },
            onUploadFailed = { reason ->
                updateSessionState {
                    it.copy(state = TransferSessionState.Failed(reason))
                }
            },
            onPairingAttemptFailed = {
                val current = _sessionState.value ?: return@WifiTransferHttpServer
                val newFailedCount = current.failedPairingAttempts + 1
                if (newFailedCount >= WifiTransferSession.MAX_FAILED_ATTEMPTS) {
                    stopSession()
                    _sessionState.value = current.copy(
                        failedPairingAttempts = newFailedCount,
                        state = TransferSessionState.Failed("Too many failed pairing attempts. Session locked out.")
                    )
                } else {
                    _sessionState.value = current.copy(failedPairingAttempts = newFailedCount)
                }
            },
            ioDispatcher = ioDispatcher
        )

        try {
            server.start()
            activeServer = server
            startCountdownTicker()
            return true
        } catch (e: Exception) {
            _sessionState.value = session.copy(
                state = TransferSessionState.Failed("Could not start upload service: ${e.message}")
            )
            return false
        }
    }

    private fun startCountdownTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                delay(1000L)
                val current = _sessionState.value ?: break
                if (current.isExpired) {
                    stopSession()
                    _sessionState.value = current.copy(state = TransferSessionState.Expired)
                    break
                }
            }
        }
    }

    /**
     * Stops the active HTTP server and cancels the session.
     */
    fun stopSession() {
        tickerJob?.cancel()
        tickerJob = null
        try {
            activeServer?.stop()
        } catch (_: Exception) {}
        activeServer = null

        val current = _sessionState.value
        if (current != null && current.state is TransferSessionState.WaitingForConnection) {
            _sessionState.value = current.copy(state = TransferSessionState.Cancelled)
        }
    }

    /**
     * Cleanly clears any completed or failed session state.
     */
    fun reset() {
        stopSession()
        _sessionState.value = null
    }

    private inline fun updateSessionState(update: (WifiTransferSession) -> WifiTransferSession) {
        val current = _sessionState.value ?: return
        _sessionState.value = update(current)
    }
}
