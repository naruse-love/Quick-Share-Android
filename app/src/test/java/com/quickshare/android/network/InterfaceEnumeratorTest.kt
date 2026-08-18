package com.quickshare.android.network

import com.quickshare.android.model.InterfaceType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Model representing network interface metadata for legacy tests.
 */
data class TestNetworkInterfaceInfo(
    val name: String,
    val displayName: String,
    val ipAddress: String,
    val isLoopback: Boolean,
    val isUp: Boolean,
    val transportType: String = "UNKNOWN"
)

/**
 * InterfaceEnumerator unit tests for local network interface detection and filtering.
 */
class InterfaceEnumeratorTest {

    object InterfaceEnumerator {
        fun enumerateInterfaces(): List<TestNetworkInterfaceInfo> {
            val result = mutableListOf<TestNetworkInterfaceInfo>()
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return result

            for (ni in interfaces.toList()) {
                val addrs = ni.inetAddresses.toList().filterIsInstance<Inet4Address>()
                for (addr in addrs) {
                    val hostAddress = addr.hostAddress ?: continue
                    val transport = classifyTransport(ni.name)
                    result.add(
                        TestNetworkInterfaceInfo(
                            name = ni.name,
                            displayName = ni.displayName ?: ni.name,
                            ipAddress = hostAddress,
                            isLoopback = ni.isLoopback || hostAddress.startsWith("127."),
                            isUp = ni.isUp,
                            transportType = transport
                        )
                    )
                }
            }
            return result
        }

        fun classifyTransport(name: String): String {
            val lower = name.lowercase()
            return when {
                lower.startsWith("wlan") || lower.startsWith("wifi") -> "WIFI"
                lower.startsWith("rndis") || lower.startsWith("usb") || lower.startsWith("ncm") -> "USB_TETHER"
                lower.startsWith("eth") || lower.startsWith("en") -> "ETHERNET"
                lower.startsWith("rmnet") || lower.startsWith("ccmni") -> "CELLULAR"
                lower.startsWith("lo") -> "LOOPBACK"
                else -> "OTHER"
            }
        }
    }

    @Test
    fun testClassifyTransportTypes() {
        assertEquals("WIFI", InterfaceEnumerator.classifyTransport("wlan0"))
        assertEquals("WIFI", InterfaceEnumerator.classifyTransport("wifi0"))
        assertEquals("USB_TETHER", InterfaceEnumerator.classifyTransport("rndis0"))
        assertEquals("USB_TETHER", InterfaceEnumerator.classifyTransport("usb0"))
        assertEquals("USB_TETHER", InterfaceEnumerator.classifyTransport("ncm0"))
        assertEquals("ETHERNET", InterfaceEnumerator.classifyTransport("eth0"))
        assertEquals("ETHERNET", InterfaceEnumerator.classifyTransport("en0"))
        assertEquals("CELLULAR", InterfaceEnumerator.classifyTransport("rmnet_data0"))
        assertEquals("LOOPBACK", InterfaceEnumerator.classifyTransport("lo"))
        assertEquals("OTHER", InterfaceEnumerator.classifyTransport("dummy0"))
    }

    @Test
    fun testInterfaceEnumeratorClassifyInterface() {
        assertEquals(InterfaceType.WIFI, com.quickshare.android.network.InterfaceEnumerator.classifyInterface("wlan0"))
        assertEquals(InterfaceType.WIFI, com.quickshare.android.network.InterfaceEnumerator.classifyInterface("wifi0"))
        assertEquals(InterfaceType.WIFI, com.quickshare.android.network.InterfaceEnumerator.classifyInterface("p2p0"))
        assertEquals(InterfaceType.USB_TETHERING, com.quickshare.android.network.InterfaceEnumerator.classifyInterface("rndis0"))
        assertEquals(InterfaceType.USB_TETHERING, com.quickshare.android.network.InterfaceEnumerator.classifyInterface("usb0"))
        assertEquals(InterfaceType.USB_TETHERING, com.quickshare.android.network.InterfaceEnumerator.classifyInterface("ncm0"))
        assertEquals(InterfaceType.USB_TETHERING, com.quickshare.android.network.InterfaceEnumerator.classifyInterface("cdc-wdm0"))
        assertEquals(InterfaceType.ETHERNET, com.quickshare.android.network.InterfaceEnumerator.classifyInterface("eth0"))
        assertEquals(InterfaceType.ETHERNET, com.quickshare.android.network.InterfaceEnumerator.classifyInterface("enp3s0"))
        assertEquals(InterfaceType.ETHERNET, com.quickshare.android.network.InterfaceEnumerator.classifyInterface("lan0"))
        assertEquals(InterfaceType.CELLULAR, com.quickshare.android.network.InterfaceEnumerator.classifyInterface("rmnet_data0"))
        assertEquals(InterfaceType.CELLULAR, com.quickshare.android.network.InterfaceEnumerator.classifyInterface("ccmni0"))
        assertEquals(InterfaceType.CELLULAR, com.quickshare.android.network.InterfaceEnumerator.classifyInterface("pdp0"))
        assertEquals(InterfaceType.OTHER, com.quickshare.android.network.InterfaceEnumerator.classifyInterface("unknown0"))
    }

    @Test
    fun testInterfaceEnumeratorVirtualVpnFiltering() {
        assertTrue(com.quickshare.android.network.InterfaceEnumerator.isVirtualOrVpn("tun0"))
        assertTrue(com.quickshare.android.network.InterfaceEnumerator.isVirtualOrVpn("tap0"))
        assertTrue(com.quickshare.android.network.InterfaceEnumerator.isVirtualOrVpn("ppp0"))
        assertTrue(com.quickshare.android.network.InterfaceEnumerator.isVirtualOrVpn("dummy0"))
        assertTrue(com.quickshare.android.network.InterfaceEnumerator.isVirtualOrVpn("vboxnet0"))
        assertTrue(com.quickshare.android.network.InterfaceEnumerator.isVirtualOrVpn("virbr0"))
        assertFalse(com.quickshare.android.network.InterfaceEnumerator.isVirtualOrVpn("wlan0"))
        assertFalse(com.quickshare.android.network.InterfaceEnumerator.isVirtualOrVpn("rndis0"))
        assertFalse(com.quickshare.android.network.InterfaceEnumerator.isVirtualOrVpn("eth0"))
    }

    @Test
    fun testInterfaceEnumerationReturnsValidList() {
        val list = InterfaceEnumerator.enumerateInterfaces()
        assertNotNull(list)
        assertTrue(list.isNotEmpty())
        val loopback = list.firstOrNull { it.ipAddress == "127.0.0.1" }
        assertNotNull(loopback)
        assertTrue(loopback?.isLoopback == true)
    }

    @Test
    fun testInterfaceEnumeratorRealScanning() {
        val enumerator = com.quickshare.android.network.InterfaceEnumerator(null)
        val loopbackList = enumerator.getAvailableInterfaces(includeLoopback = true)
        assertNotNull(loopbackList)
        assertTrue(loopbackList.isNotEmpty())
        val lo = loopbackList.firstOrNull { it.ipAddress == "127.0.0.1" }
        assertNotNull(lo)
        assertTrue(lo?.displayName?.contains("127.0.0.1") == true)
    }

    @Test
    fun testObserveInterfacesEmitsFlow() = runBlocking {
        val enumerator = com.quickshare.android.network.InterfaceEnumerator(null)
        val initialList = enumerator.observeInterfaces(includeLoopback = true).first()
        assertNotNull(initialList)
        assertTrue(initialList.isNotEmpty())
    }
}
