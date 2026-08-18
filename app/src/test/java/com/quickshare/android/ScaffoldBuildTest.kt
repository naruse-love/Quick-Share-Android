package com.quickshare.android

import com.quickshare.android.protocol.QuickShareProtocolConstants
import com.quickshare.android.model.AppConfig
import org.junit.Assert.*
import org.junit.Test

class ScaffoldBuildTest {

    @Test
    fun testAppConfigDefaults() {
        val config = AppConfig()
        assertEquals(QuickShareProtocolConstants.DEFAULT_PORT, config.port)
        assertEquals(QuickShareProtocolConstants.DEFAULT_BUFFER_COUNT, config.bufferCount)
        assertTrue(config.keepScreenOn)
        assertTrue(config.autoStartServer)
        assertFalse(config.autoStart)
        assertNotNull(config.saveDirectory)
    }

    @Test
    fun testProtocolVersionAlignment() {
        assertEquals(300, QuickShareProtocolConstants.VERSION_CODE)
        assertEquals(1048576, QuickShareProtocolConstants.BLOCK_SIZE)
    }
}
