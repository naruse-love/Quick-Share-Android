package com.quickshare.android.e2e.harness

import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * DynamicPortAllocator provides collision-free, thread-safe ephemeral TCP port allocation
 * for loopback testing across multiple test cases running concurrently.
 */
object DynamicPortAllocator {
    private val allocatedPorts = ConcurrentHashMap.newKeySet<Int>()
    private val fallbackPortCounter = AtomicInteger(35000)

    /**
     * Allocates a free TCP port on localhost.
     * Guarantees the port is currently available by briefly binding to port 0.
     */
    @Synchronized
    fun allocateFreePort(): Int {
        for (attempt in 1..20) {
            try {
                ServerSocket(0).use { socket ->
                    val port = socket.localPort
                    if (port > 1024 && allocatedPorts.add(port)) {
                        return port
                    }
                }
            } catch (_: IOException) {
                // Retry if port binding was contentious
            }
        }

        // Fallback sequentially if ephemeral binding failed
        while (true) {
            val port = fallbackPortCounter.incrementAndGet()
            if (port > 65000) {
                fallbackPortCounter.set(35000)
            }
            if (allocatedPorts.add(port)) {
                return port
            }
        }
    }

    /**
     * Releases a previously allocated port back to the pool.
     */
    fun releasePort(port: Int) {
        allocatedPorts.remove(port)
    }

    /**
     * Clears all tracked allocated ports.
     */
    fun reset() {
        allocatedPorts.clear()
    }
}
