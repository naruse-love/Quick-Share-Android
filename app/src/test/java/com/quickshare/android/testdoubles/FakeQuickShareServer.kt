package com.quickshare.android.testdoubles

import com.quickshare.android.model.NetworkInterfaceInfo
import com.quickshare.android.model.RemoteFile
import com.quickshare.android.model.TrafficInfo
import com.quickshare.android.model.TransferTask
import com.quickshare.android.network.AggregatedTrafficSnapshot
import com.quickshare.android.network.ClientSessionInfo
import com.quickshare.android.network.IQuickShareServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeQuickShareServer : IQuickShareServer {

    val _isRunning = MutableStateFlow(false)
    override val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    val _connectedClients = MutableStateFlow<List<ClientSessionInfo>>(emptyList())
    override val connectedClients: StateFlow<List<ClientSessionInfo>> = _connectedClients.asStateFlow()

    val _activeTransfers = MutableStateFlow<List<TransferTask>>(emptyList())
    override val activeTransfers: StateFlow<List<TransferTask>> = _activeTransfers.asStateFlow()

    val _serverStatusText = MutableStateFlow("服务未启动")
    override val serverStatusText: StateFlow<String> = _serverStatusText.asStateFlow()

    val _channelTraffic = MutableStateFlow<List<TrafficInfo>>(emptyList())
    override val channelTraffic: StateFlow<List<TrafficInfo>> = _channelTraffic.asStateFlow()

    val _trafficSnapshot = MutableStateFlow(AggregatedTrafficSnapshot())
    override val trafficSnapshot: StateFlow<AggregatedTrafficSnapshot> = _trafficSnapshot.asStateFlow()

    var shouldStartSucceed: Boolean = true

    override suspend fun start(
        listenPort: Int,
        activeNics: List<NetworkInterfaceInfo>,
        bindAddress: String
    ): Boolean {
        return if (shouldStartSucceed) {
            _isRunning.value = true
            _serverStatusText.value = "正在监听 0.0.0.0:$listenPort"
            true
        } else {
            _isRunning.value = false
            _serverStatusText.value = "启动失败"
            false
        }
    }

    override suspend fun stop() {
        _isRunning.value = false
        _serverStatusText.value = "已停止"
        _connectedClients.value = emptyList()
        _activeTransfers.value = emptyList()
    }

    override suspend fun listRemoteFiles(path: String): List<RemoteFile>? {
        return emptyList()
    }

    override suspend fun deleteRemoteFile(path: String): Boolean {
        return true
    }

    override suspend fun createRemoteDir(parent: String, child: String): Boolean {
        return true
    }

    override suspend fun sendFilesToRemote(
        localPaths: List<String>,
        remoteDestDir: String,
        onProgress: ((TransferTask) -> Unit)?
    ): Boolean {
        return true
    }

    override suspend fun pullFilesFromRemote(
        remotePaths: List<String>,
        remoteParentDir: String,
        localDestDir: String,
        onProgress: ((TransferTask) -> Unit)?
    ): Boolean {
        return true
    }

    override fun close() {
        _isRunning.value = false
        _serverStatusText.value = "已停止"
        _connectedClients.value = emptyList()
        _activeTransfers.value = emptyList()
    }
}
