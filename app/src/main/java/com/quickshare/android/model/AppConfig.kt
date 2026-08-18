package com.quickshare.android.model

import android.os.Environment
import java.io.File

/**
 * AppConfig contains application preferences and network/storage defaults.
 */
data class AppConfig(
    val port: Int = DEFAULT_PORT,
    val saveDirectory: String = defaultSaveDirectory,
    val autoStart: Boolean = false,
    val autoStartServer: Boolean = true,
    val boundInterfaces: List<String> = emptyList(),
    val bufferCount: Int = DEFAULT_BUFFER_COUNT,
    val keepScreenOn: Boolean = true,
    val enableSoundNotification: Boolean = true
) {
    companion object {
        const val DEFAULT_PORT: Int = 5740
        const val DEFAULT_BUFFER_COUNT: Int = 8

        val defaultSaveDirectory: String
            get() {
                return try {
                    val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    if (downloads.exists() || downloads.mkdirs()) {
                        downloads.absolutePath
                    } else {
                        "/sdcard/Download"
                    }
                } catch (_: Throwable) {
                    "/sdcard/Download"
                }
            }
    }
}
