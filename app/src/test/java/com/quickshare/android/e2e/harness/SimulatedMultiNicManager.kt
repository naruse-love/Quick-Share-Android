package com.quickshare.android.e2e.harness

import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Simulated network interface representation for multi-path testing.
 */
data class SimulatedNic(
    val name: String,
    val ipAddress: InetAddress,
    val ipLength: Byte = 4,
    val isDefault: Boolean = false,
    val isEnabled: AtomicBoolean = AtomicBoolean(true),
    val bytesSent: AtomicLong = AtomicLong(0),
    val bytesReceived: AtomicLong = AtomicLong(0),
    var latencyMs: Long = 0L,
    var failAfterBytes: Long = -1L
) {
    fun recordSent(bytes: Long) {
        bytesSent.addAndGet(bytes)
    }

    fun recordReceived(bytes: Long) {
        bytesReceived.addAndGet(bytes)
    }

    fun disable() {
        isEnabled.set(false)
    }

    fun enable() {
        isEnabled.set(true)
    }
}

/**
 * SimulatedMultiNicManager manages virtual network interfaces for multi-channel loopback testing.
 * Simulates Wi-Fi (wlan0), USB Tethering (rndis0), and Ethernet (eth0) physical links.
 */
class SimulatedMultiNicManager {
    private val nics = ConcurrentHashMap<String, SimulatedNic>()

    init {
        // Initialize default standard simulated interfaces on localhost
        val loopback = InetAddress.getByName("127.0.0.1")
        nics["wlan0"] = SimulatedNic("wlan0", loopback, 4, isDefault = true)
        nics["rndis0"] = SimulatedNic("rndis0", loopback, 4, isDefault = false)
        nics["eth0"] = SimulatedNic("eth0", loopback, 4, isDefault = false)
    }

    fun addNic(name: String, ip: InetAddress, isDefault: Boolean = false): SimulatedNic {
        val nic = SimulatedNic(name, ip, 4, isDefault)
        nics[name] = nic
        return nic
    }

    fun getNic(name: String): SimulatedNic? = nics[name]

    fun getAllNics(): List<SimulatedNic> = nics.values.toList()

    fun getActiveNics(): List<SimulatedNic> = nics.values.filter { it.isEnabled.get() }

    fun getTotalBytesSent(): Long = nics.values.sumOf { it.bytesSent.get() }

    fun getTotalBytesReceived(): Long = nics.values.sumOf { it.bytesReceived.get() }

    fun resetStats() {
        nics.values.forEach {
            it.bytesSent.set(0)
            it.bytesReceived.set(0)
            it.latencyMs = 0
            it.failAfterBytes = -1
            it.isEnabled.set(true)
        }
    }

    fun injectLatency(nicName: String, latencyMs: Long) {
        nics[nicName]?.latencyMs = latencyMs
    }

    fun injectFailureAfterBytes(nicName: String, byteThreshold: Long) {
        nics[nicName]?.failAfterBytes = byteThreshold
    }
}
