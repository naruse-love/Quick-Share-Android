package com.quickshare.android.model

import org.junit.Assert.*
import org.junit.Test

/**
 * Traffic statistics calculation and speed formatting tests matching TrafficInfo / TrafficManager.
 */
class TrafficInfoTest {

    data class TestTrafficInfo(
        val uploadSpeed: Long = 0,
        val downloadSpeed: Long = 0,
        val uploadTraffic: Long = 0,
        val downloadTraffic: Long = 0
    ) {
        companion object {
            fun formatSpeed(bytesPerSec: Long): String {
                return when {
                    bytesPerSec < 1024 -> "$bytesPerSec B/s"
                    bytesPerSec < 1024 * 1024 -> "%.2f KB/s".format(bytesPerSec / 1024.0)
                    bytesPerSec < 1024 * 1024 * 1024 -> "%.2f MB/s".format(bytesPerSec / (1024.0 * 1024.0))
                    else -> "%.2f GB/s".format(bytesPerSec / (1024.0 * 1024.0 * 1024.0))
                }
            }

            fun formatSize(bytes: Long): String {
                return when {
                    bytes < 1024 -> "$bytes B"
                    bytes < 1024 * 1024 -> "%.2f KB".format(bytes / 1024.0)
                    bytes < 1024 * 1024 * 1024 -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
                    else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
                }
            }

            fun calculateEtaSeconds(remainingBytes: Long, speedBytesPerSec: Long): Long {
                if (speedBytesPerSec <= 0 || remainingBytes <= 0) return 0L
                return remainingBytes / speedBytesPerSec
            }
        }
    }

    @Test
    fun testSpeedFormatting() {
        assertEquals("500 B/s", TestTrafficInfo.formatSpeed(500))
        assertEquals("1.00 KB/s", TestTrafficInfo.formatSpeed(1024))
        assertEquals("512.00 KB/s", TestTrafficInfo.formatSpeed(512 * 1024))
        assertEquals("1.00 MB/s", TestTrafficInfo.formatSpeed(1024 * 1024))
        assertEquals("85.50 MB/s", TestTrafficInfo.formatSpeed((85.5 * 1024 * 1024).toLong()))
        assertEquals("1.50 GB/s", TestTrafficInfo.formatSpeed((1.5 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun testSizeFormatting() {
        assertEquals("0 B", TestTrafficInfo.formatSize(0))
        assertEquals("100 B", TestTrafficInfo.formatSize(100))
        assertEquals("1.00 KB", TestTrafficInfo.formatSize(1024))
        assertEquals("10.00 MB", TestTrafficInfo.formatSize(10 * 1024 * 1024))
        assertEquals("4.00 GB", TestTrafficInfo.formatSize(4L * 1024 * 1024 * 1024))
    }

    @Test
    fun testEtaCalculation() {
        assertEquals(0L, TestTrafficInfo.calculateEtaSeconds(1000000, 0))
        assertEquals(0L, TestTrafficInfo.calculateEtaSeconds(0, 1000000))
        assertEquals(10L, TestTrafficInfo.calculateEtaSeconds(100 * 1024 * 1024L, 10 * 1024 * 1024L))
        assertEquals(60L, TestTrafficInfo.calculateEtaSeconds(60000L, 1000L))
    }

    @Test
    fun testThroughputDeltaCalculation() {
        var lastBytes = 1000000L
        val currentBytes = 2500000L
        val timeDeltaSeconds = 1.0

        val speed = ((currentBytes - lastBytes) / timeDeltaSeconds).toLong()
        assertEquals(1500000L, speed)
        assertEquals("1.43 MB/s", TestTrafficInfo.formatSpeed(speed))
    }
}
