package com.quickshare.android.network

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * MultiPath physical socket binding & TCP socket configuration unit tests.
 */
class MultiPathBindingTest {

    object SocketConfigurator {
        fun configurePerformanceSocket(socket: Socket) {
            socket.tcpNoDelay = true
            socket.sendBufferSize = 1024 * 1024
            socket.receiveBufferSize = 1024 * 1024
            socket.soTimeout = 30000
        }

        fun bindAndConnect(localIp: InetAddress?, targetIp: InetAddress, targetPort: Int, timeoutMs: Int = 5000): Socket {
            val socket = Socket()
            configurePerformanceSocket(socket)
            if (localIp != null && !localIp.isAnyLocalAddress) {
                socket.bind(InetSocketAddress(localIp, 0))
            }
            socket.connect(InetSocketAddress(targetIp, targetPort), timeoutMs)
            return socket
        }
    }

    @Test
    fun testSocketConfigurationFlags() {
        val socket = Socket()
        SocketConfigurator.configurePerformanceSocket(socket)

        assertTrue(socket.tcpNoDelay)
        assertEquals(30000, socket.soTimeout)
        assertTrue(socket.sendBufferSize >= 64 * 1024)
        assertTrue(socket.receiveBufferSize >= 64 * 1024)
        socket.close()
    }

    @Test
    fun testLoopbackSocketBindingAndConnection() {
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val serverPort = server.localPort

        val clientSocket = SocketConfigurator.bindAndConnect(
            localIp = InetAddress.getByName("127.0.0.1"),
            targetIp = InetAddress.getByName("127.0.0.1"),
            targetPort = serverPort
        )

        val accepted = server.accept()
        assertTrue(clientSocket.isConnected)
        assertTrue(accepted.isConnected)

        clientSocket.close()
        accepted.close()
        server.close()
    }
}
