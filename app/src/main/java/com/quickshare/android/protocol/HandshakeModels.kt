package com.quickshare.android.protocol

import java.net.InetAddress

/**
 * Network Interface advertised by the remote peer during handshake Step 4.
 */
data class AdvertisedNic(
    val name: String,
    val ipAddress: InetAddress,
    val clientBindAddressFlag: Byte = 0
)

/**
 * Result of a completed or failed handshake.
 */
sealed class HandshakeResult {
    data class Success(
        val remoteFileSystem: Int,
        val remoteHomeDir: String,
        val bufferCount: Int,
        val remoteNics: List<AdvertisedNic>
    ) : HandshakeResult()

    data class Failure(
        val reason: String,
        val cause: Throwable? = null
    ) : HandshakeResult()
}
