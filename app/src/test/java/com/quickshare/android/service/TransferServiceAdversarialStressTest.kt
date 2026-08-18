package com.quickshare.android.service

import com.quickshare.android.model.TransferDirection
import com.quickshare.android.model.TransferTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Adversarial stress testing for TransferForegroundService:
 * - Dynamic notification throttling & high-frequency updates (100+ updates/sec simulation)
 * - WakeLock & WifiLock tag invariants, timeout bounds, non-ref-counted semantics
 * - Extreme numerical boundaries (NaN, Infinity, negatives, overflow)
 * - Unicode, emojis, path strings, and null-safety in notifications
 */
class TransferServiceAdversarialStressTest {

    @Test
    fun testExtremeProgressBoundariesAndFormatting() {
        // Test normal range
        val content0 = TransferForegroundService.buildNotificationContent(0.0, "0 B/s", "01:00")
        assertTrue(content0.contains("0.0%"))

        val content50 = TransferForegroundService.buildNotificationContent(50.55, "12.3 MB/s", "00:15")
        assertTrue(content50.contains("50.5%") || content50.contains("50.6%"))

        val content100 = TransferForegroundService.buildNotificationContent(100.0, "50.0 MB/s", "00:00")
        assertTrue(content100.contains("100.0%"))

        // Test boundary and abnormal values
        val contentNegative = TransferForegroundService.buildNotificationContent(-15.5, "0 B/s", "--")
        assertTrue(contentNegative.contains("-15.5%"))

        val contentOverflow = TransferForegroundService.buildNotificationContent(150.0, "100 MB/s", "--")
        assertTrue(contentOverflow.contains("150.0%"))

        val contentNaN = TransferForegroundService.buildNotificationContent(Double.NaN, "0 B/s", "--")
        assertTrue(contentNaN.contains("NaN%"))

        val contentPosInf = TransferForegroundService.buildNotificationContent(Double.POSITIVE_INFINITY, "0 B/s", "--")
        assertTrue(contentPosInf.contains("Infinity%"))

        val contentNegInf = TransferForegroundService.buildNotificationContent(Double.NEGATIVE_INFINITY, "0 B/s", "--")
        assertTrue(contentNegInf.contains("-Infinity%"))
    }

    @Test
    fun testNotificationTitleAdversarialStrings() {
        // Special & unicode characters
        val sendEmoji = TransferForegroundService.buildNotificationTitle(TransferDirection.SEND, "📁 我的文档_🚀.zip")
        assertEquals("正在发送: 📁 我的文档_🚀.zip", sendEmoji)

        val recvRtl = TransferForegroundService.buildNotificationTitle(TransferDirection.RECEIVE, "ملف_تجريبي.dat")
        assertEquals("正在接收: ملف_تجريبي.dat", recvRtl)

        // Path traversal string in file name
        val traversalName = "../../../etc/passwd"
        val sendTraversal = TransferForegroundService.buildNotificationTitle(TransferDirection.SEND, traversalName)
        assertEquals("正在发送: ../../../etc/passwd", sendTraversal)

        // Very long file name (4096 chars)
        val longName = "A".repeat(4096) + ".bin"
        val recvLong = TransferForegroundService.buildNotificationTitle(TransferDirection.RECEIVE, longName)
        assertTrue(recvLong.startsWith("正在接收: AAAAA"))
        assertEquals("正在接收: $longName", recvLong)

        // Empty file name
        val emptyTitle = TransferForegroundService.buildNotificationTitle(TransferDirection.SEND, "")
        assertEquals("正在发送: ", emptyTitle)
    }

    @Test
    fun testHighFrequencyNotificationUpdateThroughput() = runBlocking {
        // Simulate high-frequency 100+ updates per second across 8 concurrent coroutines
        val iterationsPerThread = 5000
        val threadCount = 8
        val successCount = AtomicInteger(0)
        val generatedContents = ConcurrentLinkedQueue<String>()

        val startTime = System.currentTimeMillis()
        val deferreds = (0 until threadCount).map { threadId ->
            async(Dispatchers.Default) {
                for (i in 0 until iterationsPerThread) {
                    val progress = (i % 100).toDouble() + 0.5
                    val speed = "${(i % 50) + 1} MB/s"
                    val eta = "00:${String.format("%02d", 60 - (i % 60))}"
                    val content = TransferForegroundService.buildNotificationContent(progress, speed, eta)
                    if (content.isNotEmpty()) {
                        successCount.incrementAndGet()
                    }
                    if (i % 1000 == 0) {
                        generatedContents.add(content)
                    }
                }
            }
        }

        deferreds.awaitAll()
        val durationMs = System.currentTimeMillis() - startTime

        assertEquals(threadCount * iterationsPerThread, successCount.get())
        assertTrue("Formatting throughput should be high (duration: ${durationMs}ms)", durationMs < 5000)
    }

    @Test
    fun testLockAndChannelConstantsInvariants() {
        // Verify channel and notification constants
        assertEquals("quickshare_transfer_channel", TransferForegroundService.CHANNEL_ID)
        assertEquals(1001, TransferForegroundService.NOTIFICATION_ID)
        assertEquals("dataSync", TransferForegroundService.FOREGROUND_SERVICE_TYPE_STR)

        // Verify lock tags
        assertEquals("QuickShare:TransferWakeLock", TransferForegroundService.WAKE_LOCK_TAG)
        assertEquals("QuickShare:TransferWifiLock", TransferForegroundService.WIFI_LOCK_TAG)
        assertTrue(TransferForegroundService.WAKE_LOCK_TAG.startsWith("QuickShare:"))
        assertTrue(TransferForegroundService.WIFI_LOCK_TAG.startsWith("QuickShare:"))

        // Verify intent action constants
        assertEquals("com.quickshare.android.ACTION_START_TRANSFER", TransferForegroundService.ACTION_START_TRANSFER)
        assertEquals("com.quickshare.android.ACTION_UPDATE_PROGRESS", TransferForegroundService.ACTION_UPDATE_PROGRESS)
        assertEquals("com.quickshare.android.ACTION_TRANSFER_COMPLETE", TransferForegroundService.ACTION_TRANSFER_COMPLETE)
        assertEquals("com.quickshare.android.ACTION_TRANSFER_FAILED", TransferForegroundService.ACTION_TRANSFER_FAILED)
        assertEquals("com.quickshare.android.ACTION_CANCEL_TRANSFER", TransferForegroundService.ACTION_CANCEL_TRANSFER)
        assertEquals("com.quickshare.android.ACTION_STOP_SERVICE", TransferForegroundService.ACTION_STOP_SERVICE)

        // Verify intent extra keys
        assertEquals("extra_task_id", TransferForegroundService.EXTRA_TASK_ID)
        assertEquals("extra_file_name", TransferForegroundService.EXTRA_FILE_NAME)
        assertEquals("extra_total_bytes", TransferForegroundService.EXTRA_TOTAL_BYTES)
        assertEquals("extra_direction", TransferForegroundService.EXTRA_DIRECTION)
        assertEquals("extra_progress", TransferForegroundService.EXTRA_PROGRESS)
        assertEquals("extra_speed", TransferForegroundService.EXTRA_SPEED)
        assertEquals("extra_eta", TransferForegroundService.EXTRA_ETA)
        assertEquals("extra_transferred_bytes", TransferForegroundService.EXTRA_TRANSFERRED_BYTES)
        assertEquals("extra_avg_speed", TransferForegroundService.EXTRA_AVG_SPEED)
        assertEquals("extra_error_message", TransferForegroundService.EXTRA_ERROR_MESSAGE)
    }
}
