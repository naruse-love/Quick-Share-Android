package com.quickshare.android.network

import android.os.Build
import com.quickshare.android.model.NetworkInterfaceInfo
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException

interface IMultiPathSocketFactory {
    fun configurePerformanceSocket(socket: Socket)
    fun createBoundSocket(
        localNic: NetworkInterfaceInfo? = null,
        interfaceEnumerator: IInterfaceEnumerator? = null
    ): Socket
    fun createConnectedSocket(
        localNic: NetworkInterfaceInfo?,
        targetAddress: InetAddress,
        targetPort: Int,
        timeoutMs: Int = 5000,
        interfaceEnumerator: IInterfaceEnumerator? = null
    ): Socket
    fun createConnectedSocket(
        localNic: NetworkInterfaceInfo?,
        targetIp: String,
        targetPort: Int,
        timeoutMs: Int = 5000,
        interfaceEnumerator: IInterfaceEnumerator? = null
    ): Socket
}

/**
 * MultiPath socket factory providing dual-layer NIC binding:
 * Layer 1: Android OS routing binding via [android.net.Network.bindSocket].
 * Layer 2: IP endpoint binding via [Socket.bind].
 *
 * Configures high-performance TCP parameters (1MB buffer sizes, TCP_NODELAY).
 */
class MultiPathSocketFactory : IMultiPathSocketFactory {

    companion object {
        const val SOCKET_BUFFER_SIZE: Int = 4 * 1024 * 1024 // 4MB High-Throughput Socket Buffer
        const val DEFAULT_TIMEOUT_MS: Int = 30000        // 30s Read Timeout
        const val CONNECT_TIMEOUT_MS: Int = 5000        // 5s Connect Timeout
    }

    override fun configurePerformanceSocket(socket: Socket) {
        try {
            socket.tcpNoDelay = true
        } catch (_: SocketException) {}
        try {
            socket.reuseAddress = true
        } catch (_: SocketException) {}
        try {
            socket.keepAlive = true
        } catch (_: SocketException) {}
        try {
            socket.sendBufferSize = SOCKET_BUFFER_SIZE
        } catch (_: SocketException) {}
        try {
            socket.receiveBufferSize = SOCKET_BUFFER_SIZE
        } catch (_: SocketException) {}
        try {
            socket.soTimeout = DEFAULT_TIMEOUT_MS
        } catch (_: SocketException) {}
    }

    override fun createBoundSocket(
        localNic: NetworkInterfaceInfo?,
        interfaceEnumerator: IInterfaceEnumerator?
    ): Socket {
        val socket = Socket()
        configurePerformanceSocket(socket)

        // Layer 1: OS Network-level binding
        if (localNic != null && interfaceEnumerator != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                val network = interfaceEnumerator.getNetworkForInterface(localNic.name)
                network?.bindSocket(socket)
            } catch (_: Throwable) {
                // Fallback to IP binding if network handle binding fails
            }
        }

        // Layer 2: Local IP endpoint binding
        if (localNic != null && localNic.ipAddress.isNotEmpty()) {
            try {
                val localAddr = InetAddress.getByName(localNic.ipAddress)
                if (!localAddr.isAnyLocalAddress) {
                    socket.bind(InetSocketAddress(localAddr, 0))
                }
            } catch (_: Throwable) {
                // Fallback to default route if explicit bind fails
            }
        }

        return socket
    }

    override fun createConnectedSocket(
        localNic: NetworkInterfaceInfo?,
        targetAddress: InetAddress,
        targetPort: Int,
        timeoutMs: Int,
        interfaceEnumerator: IInterfaceEnumerator?
    ): Socket {
        val socket = createBoundSocket(localNic, interfaceEnumerator)
        socket.connect(InetSocketAddress(targetAddress, targetPort), timeoutMs)
        return socket
    }

    override fun createConnectedSocket(
        localNic: NetworkInterfaceInfo?,
        targetIp: String,
        targetPort: Int,
        timeoutMs: Int,
        interfaceEnumerator: IInterfaceEnumerator?
    ): Socket {
        val targetAddr = InetAddress.getByName(targetIp)
        return createConnectedSocket(localNic, targetAddr, targetPort, timeoutMs, interfaceEnumerator)
    }
}
