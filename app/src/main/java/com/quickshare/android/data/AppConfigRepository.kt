package com.quickshare.android.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.quickshare.android.model.AppConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ConnectionHistoryItem(
    val ip: String,
    val port: Int,
    val timestampMs: Long = System.currentTimeMillis()
)

interface IAppConfigRepository {
    val appConfig: StateFlow<AppConfig>
    val connectionHistory: StateFlow<List<ConnectionHistoryItem>>

    fun updateConfig(update: (AppConfig) -> AppConfig)
    fun addConnectionHistory(ip: String, port: Int)
    fun removeConnectionHistory(item: ConnectionHistoryItem)
    fun clearConnectionHistory()
}

class AppConfigRepository(
    private val context: Context,
    private val prefsName: String = "quickshare_app_prefs"
) : IAppConfigRepository {

    private val prefs: SharedPreferences = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    private val gson = Gson()

    private val _appConfig = MutableStateFlow(loadConfig())
    override val appConfig: StateFlow<AppConfig> = _appConfig.asStateFlow()

    private val _connectionHistory = MutableStateFlow(loadHistory())
    override val connectionHistory: StateFlow<List<ConnectionHistoryItem>> = _connectionHistory.asStateFlow()

    private fun loadConfig(): AppConfig {
        val port = prefs.getInt(KEY_PORT, AppConfig.DEFAULT_PORT)
        val saveDir = prefs.getString(KEY_SAVE_DIR, AppConfig.defaultSaveDirectory) ?: AppConfig.defaultSaveDirectory
        val autoStart = prefs.getBoolean(KEY_AUTO_START, false)
        val autoStartServer = prefs.getBoolean(KEY_AUTO_START_SERVER, true)
        val bufferCount = prefs.getInt(KEY_BUFFER_COUNT, AppConfig.DEFAULT_BUFFER_COUNT)
        val keepScreenOn = prefs.getBoolean(KEY_KEEP_SCREEN_ON, true)
        val enableSound = prefs.getBoolean(KEY_ENABLE_SOUND, true)

        val nicsJson = prefs.getString(KEY_BOUND_NICS, null)
        val boundNics: List<String> = if (!nicsJson.isNullOrEmpty()) {
            try {
                val type = object : TypeToken<List<String>>() {}.type
                gson.fromJson(nicsJson, type) ?: emptyList()
            } catch (_: Throwable) {
                emptyList()
            }
        } else {
            emptyList()
        }

        return AppConfig(
            port = port,
            saveDirectory = saveDir,
            autoStart = autoStart,
            autoStartServer = autoStartServer,
            boundInterfaces = boundNics,
            bufferCount = bufferCount,
            keepScreenOn = keepScreenOn,
            enableSoundNotification = enableSound
        )
    }

    private fun loadHistory(): List<ConnectionHistoryItem> {
        val json = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<ConnectionHistoryItem>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    override fun updateConfig(update: (AppConfig) -> AppConfig) {
        val newConfig = update(_appConfig.value)
        _appConfig.value = newConfig
        prefs.edit()
            .putInt(KEY_PORT, newConfig.port)
            .putString(KEY_SAVE_DIR, newConfig.saveDirectory)
            .putBoolean(KEY_AUTO_START, newConfig.autoStart)
            .putBoolean(KEY_AUTO_START_SERVER, newConfig.autoStartServer)
            .putInt(KEY_BUFFER_COUNT, newConfig.bufferCount)
            .putBoolean(KEY_KEEP_SCREEN_ON, newConfig.keepScreenOn)
            .putBoolean(KEY_ENABLE_SOUND, newConfig.enableSoundNotification)
            .putString(KEY_BOUND_NICS, gson.toJson(newConfig.boundInterfaces))
            .apply()
    }

    override fun addConnectionHistory(ip: String, port: Int) {
        if (ip.isBlank() || port !in 1..65535) return
        val current = _connectionHistory.value.toMutableList()
        current.removeAll { it.ip == ip && it.port == port }
        current.add(0, ConnectionHistoryItem(ip, port, System.currentTimeMillis()))
        if (current.size > MAX_HISTORY_ITEMS) {
            current.subList(MAX_HISTORY_ITEMS, current.size).clear()
        }
        _connectionHistory.value = current
        prefs.edit().putString(KEY_HISTORY, gson.toJson(current)).apply()
    }

    override fun removeConnectionHistory(item: ConnectionHistoryItem) {
        val current = _connectionHistory.value.toMutableList()
        current.removeAll { it.ip == item.ip && it.port == item.port }
        _connectionHistory.value = current
        prefs.edit().putString(KEY_HISTORY, gson.toJson(current)).apply()
    }

    override fun clearConnectionHistory() {
        _connectionHistory.value = emptyList()
        prefs.edit().remove(KEY_HISTORY).apply()
    }

    companion object {
        private const val KEY_PORT = "pref_port"
        private const val KEY_SAVE_DIR = "pref_save_dir"
        private const val KEY_AUTO_START = "pref_auto_start"
        private const val KEY_AUTO_START_SERVER = "pref_auto_start_server"
        private const val KEY_BUFFER_COUNT = "pref_buffer_count"
        private const val KEY_KEEP_SCREEN_ON = "pref_keep_screen_on"
        private const val KEY_ENABLE_SOUND = "pref_enable_sound"
        private const val KEY_BOUND_NICS = "pref_bound_nics"
        private const val KEY_HISTORY = "pref_conn_history"
        const val MAX_HISTORY_ITEMS = 20
    }
}
