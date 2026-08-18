package com.quickshare.android.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.quickshare.android.MainActivity
import com.quickshare.android.model.TransferDirection
import com.quickshare.android.util.NotificationHelper

class TransferForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createNotificationChannel(this)
        acquireLocks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START_TRANSFER

        when (action) {
            ACTION_STOP_SERVICE, ACTION_CANCEL_TRANSFER -> {
                releaseLocks()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_TRANSFER_COMPLETE -> {
                val fileName = intent?.getStringExtra(EXTRA_FILE_NAME) ?: "文件"
                val avgSpeed = intent?.getStringExtra(EXTRA_AVG_SPEED) ?: ""
                val notification = buildCompletionNotification(fileName, avgSpeed)
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                manager?.notify(NOTIFICATION_ID, notification)
                releaseLocks()
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_TRANSFER_FAILED -> {
                val errorMsg = intent?.getStringExtra(EXTRA_ERROR_MESSAGE) ?: "传输遇到错误"
                val notification = buildFailureNotification(errorMsg)
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                manager?.notify(NOTIFICATION_ID, notification)
                releaseLocks()
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START_TRANSFER, ACTION_UPDATE_PROGRESS -> {
                val fileName = intent?.getStringExtra(EXTRA_FILE_NAME) ?: "文件传输中"
                val directionStr = intent?.getStringExtra(EXTRA_DIRECTION)
                val direction = if (directionStr == TransferDirection.SEND.name) {
                    TransferDirection.SEND
                } else {
                    TransferDirection.RECEIVE
                }
                val progress = intent?.getDoubleExtra(EXTRA_PROGRESS, 0.0) ?: 0.0
                val speed = intent?.getStringExtra(EXTRA_SPEED) ?: "0 B/s"
                val eta = intent?.getStringExtra(EXTRA_ETA) ?: "--"

                val notification = buildProgressNotification(fileName, direction, progress, speed, eta)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                return START_STICKY
            }

            else -> return START_STICKY
        }
    }

    private fun buildProgressNotification(
        fileName: String,
        direction: TransferDirection,
        progress: Double,
        speed: String,
        eta: String
    ): Notification {
        val clickPendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                putExtra("target_tab", "dashboard")
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val cancelIntent = Intent(this, TransferForegroundService::class.java).apply {
            action = ACTION_CANCEL_TRANSFER
        }
        val cancelPendingIntent = PendingIntent.getService(
            this,
            1,
            cancelIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val iconRes = if (direction == TransferDirection.SEND) {
            android.R.drawable.stat_sys_upload
        } else {
            android.R.drawable.stat_sys_download
        }

        val title = buildNotificationTitle(direction, fileName)
        val content = buildNotificationContent(progress, speed, eta)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(iconRes)
            .setProgress(100, progress.toInt().coerceIn(0, 100), false)
            .setContentIntent(clickPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "取消",
                cancelPendingIntent
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun buildCompletionNotification(fileName: String, avgSpeed: String): Notification {
        val clickPendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                putExtra("target_tab", "dashboard")
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("传输完成: $fileName")
            .setContentText(if (avgSpeed.isNotEmpty()) "平均传输速率: $avgSpeed" else "文件传输已成功完成")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(clickPendingIntent)
            .setAutoCancel(true)
            .setOngoing(false)
            .build()
    }

    private fun buildFailureNotification(errorMessage: String): Notification {
        val clickPendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                putExtra("target_tab", "dashboard")
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("传输失败")
            .setContentText(errorMessage)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentIntent(clickPendingIntent)
            .setAutoCancel(true)
            .setOngoing(false)
            .build()
    }

    private fun acquireLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (wakeLock == null) {
                wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)?.apply {
                    setReferenceCounted(false)
                    acquire(MAX_TRANSFER_TIMEOUT_MS)
                }
            } else if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(MAX_TRANSFER_TIMEOUT_MS)
            }
        } catch (_: Throwable) {}

        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifiLock == null) {
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wm?.createWifiLock(mode, WIFI_LOCK_TAG)?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            } else if (wifiLock?.isHeld == false) {
                wifiLock?.acquire()
            }
        } catch (_: Throwable) {}
    }

    private fun releaseLocks() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (_: Throwable) {}
        try {
            wifiLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (_: Throwable) {}
    }

    override fun onDestroy() {
        releaseLocks()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "quickshare_transfer_channel"
        const val NOTIFICATION_ID = 1001
        const val FOREGROUND_SERVICE_TYPE_STR = "dataSync"
        const val WAKE_LOCK_TAG = "QuickShare:TransferWakeLock"
        const val WIFI_LOCK_TAG = "QuickShare:TransferWifiLock"
        private const val MAX_TRANSFER_TIMEOUT_MS = 6 * 60 * 60 * 1000L // 6 hours

        const val ACTION_START_TRANSFER = "com.quickshare.android.ACTION_START_TRANSFER"
        const val ACTION_UPDATE_PROGRESS = "com.quickshare.android.ACTION_UPDATE_PROGRESS"
        const val ACTION_TRANSFER_COMPLETE = "com.quickshare.android.ACTION_TRANSFER_COMPLETE"
        const val ACTION_TRANSFER_FAILED = "com.quickshare.android.ACTION_TRANSFER_FAILED"
        const val ACTION_CANCEL_TRANSFER = "com.quickshare.android.ACTION_CANCEL_TRANSFER"
        const val ACTION_STOP_SERVICE = "com.quickshare.android.ACTION_STOP_SERVICE"

        const val EXTRA_TASK_ID = "extra_task_id"
        const val EXTRA_FILE_NAME = "extra_file_name"
        const val EXTRA_TOTAL_BYTES = "extra_total_bytes"
        const val EXTRA_DIRECTION = "extra_direction"
        const val EXTRA_PROGRESS = "extra_progress"
        const val EXTRA_SPEED = "extra_speed"
        const val EXTRA_ETA = "extra_eta"
        const val EXTRA_TRANSFERRED_BYTES = "extra_transferred_bytes"
        const val EXTRA_AVG_SPEED = "extra_avg_speed"
        const val EXTRA_ERROR_MESSAGE = "extra_error_message"

        fun buildNotificationTitle(direction: TransferDirection, fileName: String): String {
            val prefix = if (direction == TransferDirection.SEND) "正在发送: " else "正在接收: "
            return "$prefix$fileName"
        }

        fun buildNotificationContent(progress: Double, speed: String, eta: String): String {
            val formattedProgress = String.format("%.1f", progress)
            return "$formattedProgress% • $speed • 剩余 $eta"
        }
    }
}
