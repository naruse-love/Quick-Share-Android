package com.quickshare.android.model

/**
 * TrafficInfo models cumulative or windowed upload and download traffic for a specific NIC.
 */
data class TrafficInfo(
    val iName: String = "",
    var uploadTraffic: Long = 0L,
    var downloadTraffic: Long = 0L
) {
    fun totalTraffic(): Long = uploadTraffic + downloadTraffic

    fun addUpload(bytes: Long) {
        uploadTraffic += bytes
    }

    fun addDownload(bytes: Long) {
        downloadTraffic += bytes
    }

    fun copyTraffic(): TrafficInfo = TrafficInfo(iName, uploadTraffic, downloadTraffic)
}
