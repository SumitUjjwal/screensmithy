package com.screensmithy.app.data.transfer

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

class NetworkUtilsTest {

    @Test
    fun findAvailablePort_returnsUsablePort() {
        val loopback = InetAddress.getByName("127.0.0.1")
        val port = NetworkUtils.findAvailablePort(loopback, preferredPort = 8080)
        assertTrue(port > 0)

        // Verify that we can bind to the port
        ServerSocket(port, 1, loopback).use { server ->
            assertTrue(server.isBound)
        }
    }

    @Test
    fun isPortAvailable_whenPortOccupied_returnsFalse() {
        val loopback = InetAddress.getByName("127.0.0.1")
        ServerSocket(0, 1, loopback).use { server ->
            val occupiedPort = server.localPort
            val available = NetworkUtils.isPortAvailable(occupiedPort, loopback)
            org.junit.Assert.assertFalse(available)
        }
    }
}
