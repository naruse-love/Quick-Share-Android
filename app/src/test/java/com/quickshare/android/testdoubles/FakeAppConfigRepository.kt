package com.quickshare.android.testdoubles

import com.quickshare.android.data.ConnectionHistoryItem
import com.quickshare.android.data.IAppConfigRepository
import com.quickshare.android.model.AppConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeAppConfigRepository(
    initialConfig: AppConfig = AppConfig(),
    initialHistory: List<ConnectionHistoryItem> = emptyList()
) : IAppConfigRepository {

    private val _appConfig = MutableStateFlow(initialConfig)
    override val appConfig: StateFlow<AppConfig> = _appConfig.asStateFlow()

    private val _connectionHistory = MutableStateFlow(initialHistory)
    override val connectionHistory: StateFlow<List<ConnectionHistoryItem>> = _connectionHistory.asStateFlow()

    override fun updateConfig(update: (AppConfig) -> AppConfig) {
        _appConfig.value = update(_appConfig.value)
    }

    override fun addConnectionHistory(ip: String, port: Int) {
        val list = _connectionHistory.value.toMutableList()
        list.removeAll { it.ip == ip && it.port == port }
        list.add(0, ConnectionHistoryItem(ip, port, System.currentTimeMillis()))
        _connectionHistory.value = list
    }

    override fun removeConnectionHistory(item: ConnectionHistoryItem) {
        val list = _connectionHistory.value.toMutableList()
        list.removeAll { it.ip == item.ip && it.port == item.port }
        _connectionHistory.value = list
    }

    override fun clearConnectionHistory() {
        _connectionHistory.value = emptyList()
    }
}
