package com.quickshare.android.network

import com.quickshare.android.e2e.harness.DynamicPortAllocator
import com.quickshare.android.model.InterfaceType
import com.quickshare.android.model.NetworkInterfaceInfo
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

class MultiPathSocketFactoryTest {

    private val socketFactory = MultiPathSocketFactory()

    @Test
    fun testConfigurePerformanceSocket() {
        val socket = Socket()
        socketFactory.configurePerformanceSocket(socket)

        assertTrue(socket.tcpNoDelay)
        assertTrue(socket.reuseAddress)
        assertTrue(socket.keepAlive)
        assertEquals(MultiPathSocketFactory.DEFAULT_TIMEOUT_MS, socket.soTimeout)
        assertTrue(socket.sendBufferSize >= 64 * 1024)
        assertTrue(socket.receiveBufferSize >= 64 * 1024)
        socket.close()
    }

    @Test
    fun testCreateBoundSocket() {
        val nicInfo = NetworkInterfaceInfo(
            name = "lo",
            ipAddress = "127.0.0.1",
            interfaceType = InterfaceType.OTHER
        )
        val socket = socketFactory.createBoundSocket(nicInfo)
        assertNotNull(socket)
        assertTrue(socket.isBound)
        assertEquals("127.0.0.1", socket.localAddress.hostAddress)
        socket.close()
    }

    @Test
    fun testCreateConnectedSocketLoopback() {
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val serverPort = server.localPort

        val nicInfo = NetworkInterfaceInfo(
            name = "lo",
            ipAddress = "127.0.0.1",
            interfaceType = InterfaceType.OTHER
        )

        val clientSocket = socketFactory.createConnectedSocket(
            localNic = nicInfo,
            targetIp = "127.0.0.1",
            targetPort = serverPort,
            timeoutMs = 5000
        )

        val serverAccepted = server.accept()
        assertTrue(clientSocket.isConnected)
        assertTrue(serverAccepted.isConnected)

        clientSocket.close()
        serverAccepted.close()
        server.close()
    }

    @Test
    fun testConnectConnectionRefused() {
        val freePort = DynamicPortAllocator.allocateFreePort()
        DynamicPortAllocator.releasePort(freePort)

        var thrown = false
        try {
            socketFactory.createConnectedSocket(
                localNic = null,
                targetAddress = InetAddress.getByName("127.0.0.1"),
                targetPort = freePort,
                timeoutMs = 1000
            )
        } catch (e: IOException) {
            thrown = true
        }
        assertTrue("Expected IOException on closed port", thrown)
    }
}
