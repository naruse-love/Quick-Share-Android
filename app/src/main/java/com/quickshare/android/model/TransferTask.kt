package com.quickshare.android.model

import java.util.UUID

enum class TransferDirection {
    RECEIVE,
    SEND;

    val displayName: String
        get() = if (this == RECEIVE) "接收" else "发送"
}

enum class TransferStatus {
    WAITING,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED;

    val displayName: String
        get() = when (this) {
            WAITING -> "等待中"
            RUNNING -> "传输中"
            COMPLETED -> "完成"
            FAILED -> "失败"
            CANCELLED -> "已取消"
        }
}

/**
 * TransferTask encapsulates transfer state for dashboard monitoring and UI binding.
 */
data class TransferTask(
    val id: String = UUID.randomUUID().toString(),
    val fileName: String = "",
    val filePath: String = "",
    val direction: TransferDirection = TransferDirection.RECEIVE,
    val size: Long = 0L,
    val bytesTransferred: Long = 0L,
    val progress: Double = 0.0,
    val speed: String = "0 KB/s",
    val bytesPerSecond: Long = 0L,
    val status: TransferStatus = TransferStatus.WAITING,
    val errorMessage: String? = null,
    val startTimeMs: Long = 0L,
    val endTimeMs: Long = 0L
) {
    fun withBytesTransferred(transferred: Long): TransferTask {
        val calcProgress = if (size > 0) (transferred.toDouble() / size.toDouble()) * 100.0 else 0.0
        return copy(
            bytesTransferred = transferred,
            progress = calcProgress.coerceIn(0.0, 100.0)
        )
    }

    fun withSpeed(speedText: String, speedBps: Long): TransferTask {
        return copy(speed = speedText, bytesPerSecond = speedBps)
    }

    fun withStatus(newStatus: TransferStatus, error: String? = null): TransferTask {
        return copy(
            status = newStatus,
            errorMessage = error,
            endTimeMs = if (newStatus == TransferStatus.COMPLETED || newStatus == TransferStatus.FAILED || newStatus == TransferStatus.CANCELLED) {
                System.currentTimeMillis()
            } else {
                endTimeMs
            }
        )
    }
}
