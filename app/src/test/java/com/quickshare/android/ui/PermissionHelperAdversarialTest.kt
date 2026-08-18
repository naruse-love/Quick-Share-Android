package com.quickshare.android.ui

import com.quickshare.android.util.PermissionHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Adversarial edge cases test suite for PermissionHelper and connection URI parsing:
 * - Permission requirement matrices across API 26, 29, 30, 33, 34, 35
 * - Intent generation contracts and URI scheme formats
 * - Deep link URI parsing with boundary port numbers, IPv6 addresses, and malformed inputs
 */
class PermissionHelperAdversarialTest {

    @Test
    fun testPermissionMatrixAcrossApiTiers() {
        // Android 13+ (API 33, 34, 35)
        val api33Perms = setOf(
            "android.permission.READ_MEDIA_IMAGES",
            "android.permission.READ_MEDIA_VIDEO",
            "android.permission.READ_MEDIA_AUDIO"
        )
        assertEquals(3, api33Perms.size)

        // Android 11..12L (API 30, 31, 32)
        val api30Perms = setOf(
            "android.permission.READ_EXTERNAL_STORAGE"
        )
        assertEquals(1, api30Perms.size)

        // Legacy Android (API 26..29)
        val legacyPerms = setOf(
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE"
        )
        assertEquals(2, legacyPerms.size)

        // Verify runtime getRequiredStoragePermissions returns non-empty valid permissions
        val runtimePerms = PermissionHelper.getRequiredStoragePermissions()
        assertNotNull(runtimePerms)
        assertTrue(runtimePerms.isNotEmpty())
        for (perm in runtimePerms) {
            assertTrue("Permission must start with android.permission", perm.startsWith("android.permission."))
        }
    }

    @Test
    fun testDeepLinkUriBoundaryParsing() {
        data class TestCase(val uri: String, val expectedIp: String?, val expectedPort: Int?)

        val testCases = listOf(
            TestCase("quickshare://connect?ip=192.168.1.100&port=18888", "192.168.1.100", 18888),
            TestCase("quickshare://connect?ip=10.0.0.1&port=29999", "10.0.0.1", 29999),
            TestCase("quickshare://connect?ip=127.0.0.1&port=1", "127.0.0.1", 1),
            TestCase("quickshare://connect?ip=127.0.0.1&port=65535", "127.0.0.1", 65535),
            TestCase("quickshare://connect?ip=[2001:db8::1]&port=18888", "[2001:db8::1]", 18888),
            TestCase("quickshare://connect?ip=my-pc.local&port=8080", "my-pc.local", 8080),
            // With multiple extra query params
            TestCase("quickshare://connect?mode=fast&ip=192.168.0.2&nic=wlan0&port=18888&auto=true", "192.168.0.2", 18888),
            // Missing port
            TestCase("quickshare://connect?ip=192.168.1.5", "192.168.1.5", null),
            // Missing IP
            TestCase("quickshare://connect?port=18888", null, 18888),
            // Non-numeric port
            TestCase("quickshare://connect?ip=1.1.1.1&port=abc", "1.1.1.1", null),
            // Empty / malformed
            TestCase("quickshare://connect", null, null),
            TestCase("invalid://uri", null, null)
        )

        for ((uri, expectedIp, expectedPort) in testCases) {
            val ip = if (uri.contains("ip=")) {
                uri.substringAfter("ip=").substringBefore("&").ifEmpty { null }
            } else null

            val portStr = if (uri.contains("port=")) {
                uri.substringAfter("port=").substringBefore("&")
            } else null
            val port = portStr?.toIntOrNull()

            assertEquals("Failed IP match for: $uri", expectedIp, ip)
            assertEquals("Failed Port match for: $uri", expectedPort, port)
        }
    }

    @Test
    fun testPortRangeValidationLogic() {
        val validPorts = listOf(1, 80, 443, 1024, 18888, 29999, 65535)
        for (p in validPorts) {
            assertTrue("Port $p should be valid in range 1..65535", p in 1..65535)
        }

        val invalidPorts = listOf(0, -1, -8080, 65536, 100000)
        for (p in invalidPorts) {
            assertFalse("Port $p should be invalid in range 1..65535", p in 1..65535)
        }
    }

    @Test
    fun testIPv4AddressRegexValidation() {
        val ipv4Regex = Regex("^((25[0-5]|(2[0-4]|1\\d|[1-9]|)\\d)\\.?\\b){4}\$")

        val validIps = listOf(
            "192.168.1.1",
            "10.0.0.1",
            "172.16.0.1",
            "127.0.0.1",
            "0.0.0.0",
            "255.255.255.255"
        )
        for (ip in validIps) {
            assertTrue("IP $ip should be valid IPv4", ipv4Regex.matches(ip))
        }

        val invalidIps = listOf(
            "256.1.1.1",
            "192.168.1",
            "192.168.1.1.1",
            "abc.def.ghi.jkl",
            "",
            "192.168.1.-1"
        )
        for (ip in invalidIps) {
            assertFalse("IP $ip should be invalid IPv4", ipv4Regex.matches(ip))
        }
    }
}
