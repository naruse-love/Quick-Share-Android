package com.quickshare.android.testdoubles

import android.net.Network
import com.quickshare.android.model.InterfaceType
import com.quickshare.android.model.NetworkInterfaceInfo
import com.quickshare.android.network.IInterfaceEnumerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeInterfaceEnumerator(
    initialNics: List<NetworkInterfaceInfo> = listOf(
        NetworkInterfaceInfo("wlan0", "192.168.1.100", InterfaceType.WIFI, true),
        NetworkInterfaceInfo("rndis0", "192.168.42.129", InterfaceType.USB_TETHERING, true),
        NetworkInterfaceInfo("eth0", "10.0.0.5", InterfaceType.ETHERNET, true)
    )
) : IInterfaceEnumerator {

    private val _nicsFlow = MutableStateFlow(initialNics)

    fun updateInterfaces(nics: List<NetworkInterfaceInfo>) {
        _nicsFlow.value = nics
    }

    override fun getAvailableInterfaces(includeLoopback: Boolean): List<NetworkInterfaceInfo> {
        return _nicsFlow.value
    }

    override fun observeInterfaces(includeLoopback: Boolean): Flow<List<NetworkInterfaceInfo>> {
        return _nicsFlow.asStateFlow()
    }

    override fun getNetworkForInterface(interfaceName: String): Network? {
        return null
    }
}
