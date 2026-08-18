package com.quickshare.android.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import com.quickshare.android.model.InterfaceType
import com.quickshare.android.model.NetworkInterfaceInfo
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.net.Inet4Address
import java.net.NetworkInterface

interface IInterfaceEnumerator {
    /**
     * Scans and returns all active IPv4 network interfaces.
     * @param includeLoopback whether to include loopback (127.0.0.1) interfaces.
     */
    fun getAvailableInterfaces(includeLoopback: Boolean = false): List<NetworkInterfaceInfo>

    /**
     * Cold Flow emitting updated interface lists upon network state changes.
     */
    fun observeInterfaces(includeLoopback: Boolean = false): Flow<List<NetworkInterfaceInfo>>

    /**
     * Resolves the underlying Android [Network] handle for a given interface name.
     */
    fun getNetworkForInterface(interfaceName: String): Network?
}

/**
 * Dual-layer network interface enumerator combining Android [ConnectivityManager]
 * with low-level [java.net.NetworkInterface] scanning.
 */
class InterfaceEnumerator(
    private val context: Context? = null
) : IInterfaceEnumerator {

    private val connectivityManager: ConnectivityManager? by lazy {
        try {
            context?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        } catch (_: Throwable) {
            null
        }
    }

    override fun getAvailableInterfaces(includeLoopback: Boolean): List<NetworkInterfaceInfo> {
        val result = mutableListOf<NetworkInterfaceInfo>()
        val networkMap = buildNetworkMap()

        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return result
            for (ni in interfaces.toList()) {
                val isUp = try { ni.isUp } catch (_: Throwable) { true }
                val isLoopback = try { ni.isLoopback } catch (_: Throwable) { false }

                if (!isUp) continue
                if (isLoopback && !includeLoopback) continue

                val lowerName = ni.name.lowercase()
                if (!includeLoopback && isVirtualOrVpn(lowerName)) continue

                val ipv4Addresses = ni.inetAddresses.toList().filterIsInstance<Inet4Address>()
                for (addr in ipv4Addresses) {
                    val hostAddress = addr.hostAddress ?: continue
                    val isLoopbackAddr = addr.isLoopbackAddress || hostAddress.startsWith("127.")

                    if (isLoopbackAddr && !includeLoopback) continue
                    if (!includeLoopback && (addr.isLinkLocalAddress || addr.isAnyLocalAddress)) continue

                    val matchedNetwork = networkMap[ni.name]
                    val capabilities = matchedNetwork?.let { getCapabilities(it) }
                    val interfaceType = classifyInterface(ni.name, capabilities)
                    val networkHandle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && matchedNetwork != null) {
                        try { matchedNetwork.networkHandle } catch (_: Throwable) { null }
                    } else null

                    result.add(
                        NetworkInterfaceInfo(
                            name = ni.name,
                            ipAddress = hostAddress,
                            interfaceType = interfaceType,
                            isSelected = true,
                            networkHandle = networkHandle
                        )
                    )
                }
            }
        } catch (_: Throwable) {
            // Fallback gracefully on reflection or security restrictions
        }

        return result
    }

    override fun observeInterfaces(includeLoopback: Boolean): Flow<List<NetworkInterfaceInfo>> = callbackFlow {
        // Emit initial snapshot
        trySend(getAvailableInterfaces(includeLoopback))

        val cm = connectivityManager
        if (cm == null) {
            awaitClose { }
            return@callbackFlow
        }

        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    trySend(getAvailableInterfaces(includeLoopback))
                }

                override fun onLost(network: Network) {
                    trySend(getAvailableInterfaces(includeLoopback))
                }

                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    trySend(getAvailableInterfaces(includeLoopback))
                }
            }

            cm.registerNetworkCallback(request, callback)

            awaitClose {
                try {
                    cm.unregisterNetworkCallback(callback)
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {
            awaitClose { }
        }
    }

    override fun getNetworkForInterface(interfaceName: String): Network? {
        val cm = connectivityManager ?: return null
        return try {
            val networks = cm.allNetworks
            for (network in networks) {
                val lp = cm.getLinkProperties(network)
                if (lp?.interfaceName == interfaceName) {
                    return network
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun buildNetworkMap(): Map<String, Network> {
        val map = mutableMapOf<String, Network>()
        val cm = connectivityManager ?: return map
        try {
            for (net in cm.allNetworks) {
                val lp = cm.getLinkProperties(net)
                val ifaceName = lp?.interfaceName
                if (!ifaceName.isNullOrEmpty()) {
                    map[ifaceName] = net
                }
            }
        } catch (_: Throwable) {}
        return map
    }

    private fun getCapabilities(network: Network): NetworkCapabilities? {
        return try {
            connectivityManager?.getNetworkCapabilities(network)
        } catch (_: Throwable) {
            null
        }
    }

    companion object {
        fun classifyInterface(name: String, capabilities: NetworkCapabilities? = null): InterfaceType {
            val lower = name.lowercase()
            return when {
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
                lower.startsWith("wlan") || lower.startsWith("wifi") || lower.startsWith("p2p") ->
                    InterfaceType.WIFI

                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_USB) == true ||
                lower.startsWith("rndis") || lower.startsWith("usb") || lower.startsWith("ncm") || lower.startsWith("cdc") ->
                    InterfaceType.USB_TETHERING

                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true ||
                lower.startsWith("eth") || lower.startsWith("en") || lower.startsWith("lan") ->
                    InterfaceType.ETHERNET

                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true ||
                lower.startsWith("rmnet") || lower.startsWith("ccmni") || lower.startsWith("pdp") ->
                    InterfaceType.CELLULAR

                else -> InterfaceType.OTHER
            }
        }

        fun isVirtualOrVpn(name: String): Boolean {
            val lower = name.lowercase()
            return lower.startsWith("tun") || lower.startsWith("tap") || lower.startsWith("ppp") ||
                    lower.startsWith("dummy") || lower.startsWith("vbox") || lower.startsWith("vir")
        }
    }
}
