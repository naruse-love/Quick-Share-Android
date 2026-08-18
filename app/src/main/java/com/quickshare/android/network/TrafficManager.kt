package com.quickshare.android.network

import com.quickshare.android.model.TrafficInfo
import com.quickshare.android.model.TransferDirection
import com.quickshare.android.transfer.TransferConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Snapshot of traffic metrics for a single physical network channel.
 */
data class ChannelTrafficSnapshot(
    val iName: String,
    val uploadSpeedBps: Long,
    val downloadSpeedBps: Long,
    val cumulativeUploadBytes: Long,
    val cumulativeDownloadBytes: Long,
    val formattedSpeed: String
)

/**
 * Snapshot of aggregate traffic metrics across all channels for a transfer task.
 */
data class AggregatedTrafficSnapshot(
    val timestampMs: Long = System.currentTimeMillis(),
    val totalUploadSpeedBps: Long = 0L,
    val totalDownloadSpeedBps: Long = 0L,
    val totalCumulativeBytes: Long = 0L,
    val totalTaskSize: Long = 0L,
    val progressPercent: Double = 0.0,
    val formattedSpeed: String = "0 B/s",
    val formattedTransferred: String = "0 B",
    val formattedTotalSize: String = "0 B",
    val etaSeconds: Long = 0L,
    val formattedEta: String = "--",
    val channelSnapshots: List<ChannelTrafficSnapshot> = emptyList()
)

interface ITrafficManager {
    val trafficState: StateFlow<AggregatedTrafficSnapshot>

    fun startMonitoring(
        connections: List<TransferConnection>,
        taskTotalSize: Long,
        direction: TransferDirection,
        transferredBytesProvider: () -> Long,
        coroutineScope: CoroutineScope
    )

    fun stopMonitoring()
    fun reset()
}

/**
 * Real-time 1-second sliding window throughput metering for high-speed LAN file transfers.
 * Computes smoothed ETA using Exponential Moving Average (EMA) and formats binary 1024 speeds.
 */
class TrafficManager : ITrafficManager {

    private val _trafficState = MutableStateFlow(AggregatedTrafficSnapshot())
    override val trafficState: StateFlow<AggregatedTrafficSnapshot> = _trafficState.asStateFlow()

    private var monitorJob: Job? = null
    private var smoothedSpeedBps: Double = 0.0
    private val alpha: Double = 0.35

    override fun startMonitoring(
        connections: List<TransferConnection>,
        taskTotalSize: Long,
        direction: TransferDirection,
        transferredBytesProvider: () -> Long,
        coroutineScope: CoroutineScope
    ) {
        stopMonitoring()
        smoothedSpeedBps = 0.0

        monitorJob = coroutineScope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(1000)

                var currentWindowUploadTotal = 0L
                var currentWindowDownloadTotal = 0L
                val channelList = mutableListOf<ChannelTrafficSnapshot>()

                for (conn in connections) {
                    val windowTraffic = conn.resetCurrentTrafficInfo()
                    val cumulative = conn.getTotalTraffic()

                    currentWindowUploadTotal += windowTraffic.uploadTraffic
                    currentWindowDownloadTotal += windowTraffic.downloadTraffic

                    val channelSpeed = if (direction == TransferDirection.SEND) {
                        windowTraffic.uploadTraffic
                    } else {
                        windowTraffic.downloadTraffic
                    }

                    channelList.add(
                        ChannelTrafficSnapshot(
                            iName = conn.iName,
                            uploadSpeedBps = windowTraffic.uploadTraffic,
                            downloadSpeedBps = windowTraffic.downloadTraffic,
                            cumulativeUploadBytes = cumulative.uploadTraffic,
                            cumulativeDownloadBytes = cumulative.downloadTraffic,
                            formattedSpeed = formatSpeed(channelSpeed)
                        )
                    )
                }

                val currentSpeed = if (direction == TransferDirection.SEND) {
                    currentWindowUploadTotal
                } else {
                    currentWindowDownloadTotal
                }

                if (smoothedSpeedBps == 0.0) {
                    smoothedSpeedBps = currentSpeed.toDouble()
                } else {
                    smoothedSpeedBps = alpha * currentSpeed + (1.0 - alpha) * smoothedSpeedBps
                }

                val transferred = transferredBytesProvider()
                val progress = if (taskTotalSize > 0) {
                    ((transferred.toDouble() / taskTotalSize.toDouble()) * 100.0).coerceIn(0.0, 100.0)
                } else 0.0

                val remainingBytes = (taskTotalSize - transferred).coerceAtLeast(0L)
                val effectiveSpeed = smoothedSpeedBps.toLong().coerceAtLeast(0L)
                val etaSec = if (effectiveSpeed > 0 && remainingBytes > 0) {
                    remainingBytes / effectiveSpeed
                } else 0L

                _trafficState.value = AggregatedTrafficSnapshot(
                    timestampMs = System.currentTimeMillis(),
                    totalUploadSpeedBps = currentWindowUploadTotal,
                    totalDownloadSpeedBps = currentWindowDownloadTotal,
                    totalCumulativeBytes = transferred,
                    totalTaskSize = taskTotalSize,
                    progressPercent = progress,
                    formattedSpeed = formatSpeed(currentSpeed),
                    formattedTransferred = formatSize(transferred),
                    formattedTotalSize = formatSize(taskTotalSize),
                    etaSeconds = etaSec,
                    formattedEta = formatEta(etaSec),
                    channelSnapshots = channelList
                )
            }
        }
    }

    override fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
    }

    override fun reset() {
        stopMonitoring()
        smoothedSpeedBps = 0.0
        _trafficState.value = AggregatedTrafficSnapshot()
    }

    companion object {
        fun formatSpeed(bytesPerSec: Long): String {
            val speed = bytesPerSec.toDouble()
            return when {
                speed < 1024 -> String.format(Locale.US, "%.0f B/s", speed)
                speed < 1024 * 1024 -> String.format(Locale.US, "%.1f KB/s", speed / 1024.0)
                speed < 1024 * 1024 * 1024 -> String.format(Locale.US, "%.1f MB/s", speed / (1024.0 * 1024.0))
                else -> String.format(Locale.US, "%.1f GB/s", speed / (1024.0 * 1024.0 * 1024.0))
            }
        }

        fun formatSize(bytes: Long): String {
            val size = bytes.toDouble()
            return when {
                size < 1024 -> String.format(Locale.US, "%d B", bytes)
                size < 1024 * 1024 -> String.format(Locale.US, "%.2f KB", size / 1024.0)
                size < 1024 * 1024 * 1024 -> String.format(Locale.US, "%.2f MB", size / (1024.0 * 1024.0))
                else -> String.format(Locale.US, "%.2f GB", size / (1024.0 * 1024.0 * 1024.0))
            }
        }

        fun formatEta(seconds: Long): String {
            if (seconds <= 0) return "--"
            return when {
                seconds < 60 -> "${seconds}秒"
                seconds < 3600 -> {
                    val mins = seconds / 60
                    val secs = seconds % 60
                    "${mins}分 ${secs}秒"
                }
                else -> {
                    val hours = seconds / 3600
                    val mins = (seconds % 3600) / 60
                    val secs = seconds % 60
                    "${hours}小时 ${mins}分 ${secs}秒"
                }
            }
        }
    }
}
