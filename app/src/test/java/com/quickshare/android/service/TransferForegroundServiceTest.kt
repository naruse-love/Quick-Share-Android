package com.quickshare.android.service

import com.quickshare.android.model.TransferDirection
import com.quickshare.android.model.TransferTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferForegroundServiceTest {

    @Test
    fun testNotificationChannelConstants() {
        assertEquals("quickshare_transfer_channel", TransferForegroundService.CHANNEL_ID)
        assertEquals(1001, TransferForegroundService.NOTIFICATION_ID)
    }

    @Test
    fun testForegroundServiceTypeCompatibility() {
        assertEquals("dataSync", TransferForegroundService.FOREGROUND_SERVICE_TYPE_STR)
    }

    @Test
    fun testWakeLockTagFormat() {
        assertEquals("QuickShare:TransferWakeLock", TransferForegroundService.WAKE_LOCK_TAG)
    }

    @Test
    fun testWifiLockTagFormat() {
        assertEquals("QuickShare:TransferWifiLock", TransferForegroundService.WIFI_LOCK_TAG)
    }

    @Test
    fun testIntentActions() {
        assertEquals("com.quickshare.android.ACTION_START_TRANSFER", TransferForegroundService.ACTION_START_TRANSFER)
        assertEquals("com.quickshare.android.ACTION_UPDATE_PROGRESS", TransferForegroundService.ACTION_UPDATE_PROGRESS)
        assertEquals("com.quickshare.android.ACTION_TRANSFER_COMPLETE", TransferForegroundService.ACTION_TRANSFER_COMPLETE)
        assertEquals("com.quickshare.android.ACTION_TRANSFER_FAILED", TransferForegroundService.ACTION_TRANSFER_FAILED)
        assertEquals("com.quickshare.android.ACTION_CANCEL_TRANSFER", TransferForegroundService.ACTION_CANCEL_TRANSFER)
        assertEquals("com.quickshare.android.ACTION_STOP_SERVICE", TransferForegroundService.ACTION_STOP_SERVICE)
    }

    @Test
    fun testProgressNotificationFormatting() {
        val task = TransferTask(
            fileName = "archive.tar.gz",
            direction = TransferDirection.RECEIVE,
            size = 100 * 1024 * 1024L
        ).withBytesTransferred(45 * 1024 * 1024L).withSpeed("15.2 MB/s", 15200000L)

        val title = TransferForegroundService.buildNotificationTitle(task.direction, task.fileName)
        val content = TransferForegroundService.buildNotificationContent(task.progress, task.speed, "00:03")

        assertEquals("正在接收: archive.tar.gz", title)
        assertTrue(content.contains("45.0%"))
        assertTrue(content.contains("15.2 MB/s"))
        assertTrue(content.contains("00:03"))

        val sendTitle = TransferForegroundService.buildNotificationTitle(TransferDirection.SEND, "video.mp4")
        assertEquals("正在发送: video.mp4", sendTitle)
    }
}
