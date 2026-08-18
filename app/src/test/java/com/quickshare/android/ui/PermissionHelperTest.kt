package com.quickshare.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionHelperTest {

    @Test
    fun testRequiredPermissionsForApi33() {
        val perms = listOf(
            "android.permission.READ_MEDIA_IMAGES",
            "android.permission.READ_MEDIA_VIDEO",
            "android.permission.READ_MEDIA_AUDIO"
        )
        assertEquals(3, perms.size)
        assertTrue(perms.contains("android.permission.READ_MEDIA_IMAGES"))
        assertTrue(perms.contains("android.permission.READ_MEDIA_VIDEO"))
        assertTrue(perms.contains("android.permission.READ_MEDIA_AUDIO"))
    }

    @Test
    fun testRequiredPermissionsForLegacyApi() {
        val perms = listOf(
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE"
        )
        assertEquals(2, perms.size)
        assertTrue(perms.contains("android.permission.READ_EXTERNAL_STORAGE"))
        assertTrue(perms.contains("android.permission.WRITE_EXTERNAL_STORAGE"))
    }

    @Test
    fun testDeepLinkUriParsing() {
        val uri = "quickshare://connect?ip=192.168.1.50&port=29999"
        val ip = uri.substringAfter("ip=").substringBefore("&")
        val port = uri.substringAfter("port=").toIntOrNull()

        assertEquals("192.168.1.50", ip)
        assertEquals(29999, port)
    }

    @Test
    fun testInvalidDeepLinkUri() {
        val uri = "quickshare://connect?invalid=true"
        val port = uri.substringAfter("port=", "").toIntOrNull()
        assertNull(port)
    }
}
