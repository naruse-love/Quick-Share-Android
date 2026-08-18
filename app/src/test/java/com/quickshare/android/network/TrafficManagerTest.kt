package com.quickshare.android.network

import com.quickshare.android.model.TransferDirection
import com.quickshare.android.protocol.QuickShareStream
import com.quickshare.android.transfer.TransferConnection
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class TrafficManagerTest {

    @Test
    fun testFormatSpeed() {
        assertEquals("0 B/s", TrafficManager.formatSpeed(0))
        assertEquals("512 B/s", TrafficManager.formatSpeed(512))
        assertEquals("1.0 KB/s", TrafficManager.formatSpeed(1024))
        assertEquals("12.5 KB/s", TrafficManager.formatSpeed((12.5 * 1024).toLong()))
        assertEquals("85.3 MB/s", TrafficManager.formatSpeed((85.3 * 1024 * 1024).toLong()))
        assertEquals("1.2 GB/s", TrafficManager.formatSpeed((1.2 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun testFormatSize() {
        assertEquals("0 B", TrafficManager.formatSize(0))
        assertEquals("512 B", TrafficManager.formatSize(512))
        assertEquals("1.00 KB", TrafficManager.formatSize(1024))
        assertEquals("10.50 MB", TrafficManager.formatSize((10.5 * 1024 * 1024).toLong()))
        assertEquals("2.50 GB", TrafficManager.formatSize((2.5 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun testFormatEta() {
        assertEquals("--", TrafficManager.formatEta(0))
        assertEquals("--", TrafficManager.formatEta(-10))
        assertEquals("45秒", TrafficManager.formatEta(45))
        assertEquals("2分 5秒", TrafficManager.formatEta(125))
        assertEquals("1小时 2分 5秒", TrafficManager.formatEta(3725))
    }

    @Test
    fun testTrafficManagerMonitoringAndAggregation() {
        runBlocking {
            val trafficManager = TrafficManager()

            val mockStream1 = QuickShareStream(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream())
            val mockStream2 = QuickShareStream(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream())

            val conn1 = TransferConnection("wlan0", mockStream1)
            val conn2 = TransferConnection("rndis0", mockStream2)

            var transferredBytes = 0L
            val totalSize = 10 * 1024 * 1024L // 10MB

            trafficManager.startMonitoring(
                connections = listOf(conn1, conn2),
                taskTotalSize = totalSize,
                direction = TransferDirection.SEND,
                transferredBytesProvider = { transferredBytes },
                coroutineScope = this
            )

            // Simulate traffic on channel 1 and 2
            conn1.addUploadedBytes(1024 * 1024) // 1MB
            conn2.addUploadedBytes(2 * 1024 * 1024) // 2MB
            transferredBytes = 3 * 1024 * 1024

            delay(1200)

            val snapshot = trafficManager.trafficState.value
            assertEquals(3 * 1024 * 1024L, snapshot.totalUploadSpeedBps)
            assertEquals(3 * 1024 * 1024L, snapshot.totalCumulativeBytes)
            assertEquals(totalSize, snapshot.totalTaskSize)
            assertEquals(30.0, snapshot.progressPercent, 0.1)
            assertEquals(2, snapshot.channelSnapshots.size)

            val wlanSnapshot = snapshot.channelSnapshots.first { it.iName == "wlan0" }
            assertEquals(1024 * 1024L, wlanSnapshot.uploadSpeedBps)
            val rndisSnapshot = snapshot.channelSnapshots.first { it.iName == "rndis0" }
            assertEquals(2 * 1024 * 1024L, rndisSnapshot.uploadSpeedBps)

            trafficManager.stopMonitoring()
            trafficManager.reset()

            val resetSnapshot = trafficManager.trafficState.value
            assertEquals(0L, resetSnapshot.totalUploadSpeedBps)
            assertEquals(0L, resetSnapshot.totalCumulativeBytes)
        }
    }
}
