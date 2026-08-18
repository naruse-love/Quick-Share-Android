package com.quickshare.android.model

enum class InterfaceType {
    WIFI,
    USB_TETHERING,
    ETHERNET,
    CELLULAR,
    OTHER;

    val displayName: String
        get() = when (this) {
            WIFI -> "Wi-Fi"
            USB_TETHERING -> "USB 共享"
            ETHERNET -> "以太网"
            CELLULAR -> "移动数据"
            OTHER -> "其他"
        }
}

/**
 * NetworkInterfaceInfo represents a local physical network adapter eligible for multi-path binding.
 */
data class NetworkInterfaceInfo(
    val name: String = "",
    val ipAddress: String = "",
    val interfaceType: InterfaceType = InterfaceType.WIFI,
    val isSelected: Boolean = true,
    val networkHandle: Long? = null
) {
    val displayName: String
        get() = "${interfaceType.displayName} ($name: $ipAddress)"
}
