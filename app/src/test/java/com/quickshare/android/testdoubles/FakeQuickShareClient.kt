package com.quickshare.android.testdoubles

import com.quickshare.android.model.NetworkInterfaceInfo
import com.quickshare.android.model.RemoteFile
import com.quickshare.android.model.TrafficInfo
import com.quickshare.android.model.TransferTask
import com.quickshare.android.network.AggregatedTrafficSnapshot
import com.quickshare.android.network.IQuickShareClient
import com.quickshare.android.network.IInterfaceEnumerator
import com.quickshare.android.protocol.HandshakeResult
import com.quickshare.android.protocol.QuickShareProtocolConstants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeQuickShareClient : IQuickShareClient {

    val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    val _connectedServerIp = MutableStateFlow("")
    override val connectedServerIp: StateFlow<String> = _connectedServerIp.asStateFlow()

    val _remoteFileSystem = MutableStateFlow(QuickShareProtocolConstants.FILE_SYSTEM_WINDOWS)
    override val remoteFileSystem: StateFlow<Int> = _remoteFileSystem.asStateFlow()

    val _remoteHomeDir = MutableStateFlow("C:\\Users\\Public")
    override val remoteHomeDir: StateFlow<String> = _remoteHomeDir.asStateFlow()

    val _currentTask = MutableStateFlow<TransferTask?>(null)
    override val currentTask: StateFlow<TransferTask?> = _currentTask.asStateFlow()

    val _channelTraffic = MutableStateFlow<List<TrafficInfo>>(emptyList())
    override val channelTraffic: StateFlow<List<TrafficInfo>> = _channelTraffic.asStateFlow()

    val _trafficSnapshot = MutableStateFlow(AggregatedTrafficSnapshot())
    override val trafficSnapshot: StateFlow<AggregatedTrafficSnapshot> = _trafficSnapshot.asStateFlow()

    var shouldConnectSucceed: Boolean = true
    var failureReason: String = "Connection refused"
    var remoteFilesToReturn: List<RemoteFile>? = listOf(
        RemoteFile("Folder1", "C:\\Users\\Public\\Folder1", 1000L, 0L, true),
        RemoteFile("file1.txt", "C:\\Users\\Public\\file1.txt", 2000L, 1024L, false),
        RemoteFile("file2.jpg", "C:\\Users\\Public\\file2.jpg", 3000L, 2048L, false)
    )

    override suspend fun connect(
        targetIp: String,
        targetPort: Int,
        selectedNics: List<NetworkInterfaceInfo>,
        timeoutMs: Int,
        interfaceEnumerator: IInterfaceEnumerator?,
        localHomeDir: String
    ): HandshakeResult {
        return if (shouldConnectSucceed) {
            _isConnected.value = true
            _connectedServerIp.value = targetIp
            HandshakeResult.Success(
                remoteFileSystem = _remoteFileSystem.value,
                remoteHomeDir = _remoteHomeDir.value,
                bufferCount = 128,
                remoteNics = emptyList()
            )
        } else {
            _isConnected.value = false
            HandshakeResult.Failure(failureReason)
        }
    }

    override suspend fun listRemoteFiles(remotePath: String): List<RemoteFile>? {
        return remoteFilesToReturn
    }

    override suspend fun makeRemoteDir(parentPath: String, childName: String): Boolean {
        return true
    }

    override suspend fun deleteRemoteFile(remotePath: String): Boolean {
        return true
    }

    override suspend fun sendFiles(
        localPaths: List<String>,
        remoteDestDir: String,
        onProgress: ((TransferTask) -> Unit)?
    ): Boolean {
        return true
    }

    override suspend fun receiveFiles(
        remotePaths: List<String>,
        remoteParentDir: String,
        localDestDir: String,
        onProgress: ((TransferTask) -> Unit)?
    ): Boolean {
        return true
    }

    override suspend fun disconnect() {
        _isConnected.value = false
        _connectedServerIp.value = ""
        _currentTask.value = null
    }

    override fun close() {
        _isConnected.value = false
        _connectedServerIp.value = ""
        _currentTask.value = null
    }
}
