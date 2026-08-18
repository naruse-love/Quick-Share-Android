package com.quickshare.android.transfer

import com.quickshare.android.model.TrafficInfo
import com.quickshare.android.protocol.IQuickShareStream
import java.io.Closeable

/**
 * Thread-safe wrapper holding a per-channel network stream ([IQuickShareStream]) and its
 * instantaneous and cumulative traffic metrics for a physical network interface.
 */
class TransferConnection(
    val iName: String,
    val channel: IQuickShareStream
) : Closeable {

    private val lock = Any()
    private var currentTraffic = TrafficInfo(iName)
    private var totalTraffic = TrafficInfo(iName)

    /**
     * Atomically adds uploaded bytes to both current window and total cumulative traffic.
     */
    fun addUploadedBytes(byteCount: Long) = synchronized(lock) {
        currentTraffic.uploadTraffic += byteCount
        totalTraffic.uploadTraffic += byteCount
    }

    /**
     * Atomically adds downloaded bytes to both current window and total cumulative traffic.
     */
    fun addDownloadedBytes(byteCount: Long) = synchronized(lock) {
        currentTraffic.downloadTraffic += byteCount
        totalTraffic.downloadTraffic += byteCount
    }

    /**
     * Resets and returns the traffic recorded in the current metering window.
     */
    fun resetCurrentTrafficInfo(): TrafficInfo = synchronized(lock) {
        val info = currentTraffic.copyTraffic()
        currentTraffic = TrafficInfo(iName)
        info
    }

    /**
     * Resets and returns the total cumulative traffic.
     */
    fun resetTotalTrafficInfo(): TrafficInfo = synchronized(lock) {
        val info = totalTraffic.copyTraffic()
        totalTraffic = TrafficInfo(iName)
        info
    }

    /**
     * Returns a snapshot copy of the total cumulative traffic.
     */
    fun getTotalTraffic(): TrafficInfo = synchronized(lock) {
        totalTraffic.copyTraffic()
    }

    /**
     * Closes the underlying [IQuickShareStream].
     */
    override fun close() {
        try {
            channel.close()
        } catch (_: Throwable) {}
    }
}
