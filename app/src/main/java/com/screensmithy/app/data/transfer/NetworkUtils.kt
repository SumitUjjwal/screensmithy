package com.screensmithy.app.data.transfer

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.SocketException

/**
 * Utility functions for local network interface resolution and safe port allocation.
 */
object NetworkUtils {

    private const val PREFERRED_PORT = 8080
    private const val MAX_PORT_SEARCH_ATTEMPTS = 20

    /**
     * Resolves the primary local IPv4 network address of the active Wi-Fi or Ethernet interface.
     * Returns null if the device is offline or only loopback is available.
     */
    fun getLocalIpAddress(): InetAddress? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            val candidates = mutableListOf<InetAddress>()

            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (!networkInterface.isUp || networkInterface.isLoopback || networkInterface.isVirtual) {
                    continue
                }

                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        // Prioritize Wi-Fi (wlan) and Ethernet (eth) interfaces
                        val name = networkInterface.name.lowercase()
                        if (name.startsWith("wlan") || name.startsWith("eth")) {
                            return address
                        }
                        candidates.add(address)
                    }
                }
            }

            return candidates.firstOrNull()
        } catch (_: SocketException) {
            return null
        }
    }

    /**
     * Safely locates an available TCP port bound to [bindAddress].
     * Starts with [preferredPort] and searches sequentially, or falls back to OS ephemeral allocation.
     */
    fun findAvailablePort(bindAddress: InetAddress, preferredPort: Int = PREFERRED_PORT): Int {
        for (port in preferredPort until (preferredPort + MAX_PORT_SEARCH_ATTEMPTS)) {
            if (isPortAvailable(port, bindAddress)) {
                return port
            }
        }

        // Fallback: let OS assign an ephemeral available port
        return ServerSocket(0, 1, bindAddress).use { it.localPort }
    }

    /**
     * Checks if a TCP port is currently free to bind on [bindAddress].
     */
    fun isPortAvailable(port: Int, bindAddress: InetAddress): Boolean {
        return try {
            ServerSocket().use { socket ->
                socket.reuseAddress = true
                socket.bind(java.net.InetSocketAddress(bindAddress, port), 1)
            }
            true
        } catch (_: Exception) {
            false
        }
    }
}
