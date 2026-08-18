package com.quickshare.android.data

import com.quickshare.android.model.AppConfig
import com.quickshare.android.testdoubles.FakeAppConfigRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppConfigRepositoryTest {

    @Test
    fun testDefaultAppConfigValues() {
        val config = AppConfig()
        assertEquals(AppConfig.DEFAULT_PORT, config.port)
        assertEquals("/sdcard/Download", config.saveDirectory)
        assertFalse(config.autoStart)
        assertTrue(config.autoStartServer)
        assertEquals(AppConfig.DEFAULT_BUFFER_COUNT, config.bufferCount)
        assertTrue(config.keepScreenOn)
        assertTrue(config.enableSoundNotification)
        assertTrue(config.boundInterfaces.isEmpty())
    }

    @Test
    fun testConnectionHistoryItemDataClass() {
        val item = ConnectionHistoryItem("192.168.1.100", 18888, 123456789L)
        assertEquals("192.168.1.100", item.ip)
        assertEquals(18888, item.port)
        assertEquals(123456789L, item.timestampMs)
    }

    @Test
    fun testFakeAppConfigRepositoryOperations() {
        val repo = FakeAppConfigRepository()
        assertEquals(AppConfig.DEFAULT_PORT, repo.appConfig.value.port)

        repo.updateConfig { it.copy(port = 29999, saveDirectory = "/sdcard/Custom") }
        assertEquals(29999, repo.appConfig.value.port)
        assertEquals("/sdcard/Custom", repo.appConfig.value.saveDirectory)

        // History operations
        repo.addConnectionHistory("192.168.1.10", 18888)
        repo.addConnectionHistory("192.168.1.20", 29999)
        assertEquals(2, repo.connectionHistory.value.size)
        assertEquals("192.168.1.20", repo.connectionHistory.value.first().ip)

        // Deduplication and moving to front
        repo.addConnectionHistory("192.168.1.10", 18888)
        assertEquals(2, repo.connectionHistory.value.size)
        assertEquals("192.168.1.10", repo.connectionHistory.value.first().ip)

        // Remove item
        val toRemove = repo.connectionHistory.value.first()
        repo.removeConnectionHistory(toRemove)
        assertEquals(1, repo.connectionHistory.value.size)
        assertEquals("192.168.1.20", repo.connectionHistory.value.first().ip)

        // Clear history
        repo.clearConnectionHistory()
        assertTrue(repo.connectionHistory.value.isEmpty())
    }
}
