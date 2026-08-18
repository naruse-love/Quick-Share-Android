package com.quickshare.android.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quickshare.android.data.ConnectionHistoryItem
import com.quickshare.android.data.IAppConfigRepository
import com.quickshare.android.model.NetworkInterfaceInfo
import com.quickshare.android.network.IQuickShareClient
import com.quickshare.android.network.IInterfaceEnumerator
import com.quickshare.android.protocol.HandshakeResult
import com.quickshare.android.protocol.QuickShareProtocolConstants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ClientConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

data class ConnectionUiState(
    val targetIp: String = "",
    val targetPort: String = "18888",
    val ipError: String? = null,
    val portError: String? = null,
    val status: ClientConnectionStatus = ClientConnectionStatus.DISCONNECTED,
    val connectedIp: String = "",
    val remoteFsName: String = "",
    val remoteHomeDir: String = "",
    val availableNics: List<NetworkInterfaceInfo> = emptyList(),
    val connectionHistory: List<ConnectionHistoryItem> = emptyList(),
    val errorMessage: String? = null,
    val isConnecting: Boolean = false,
    val portPresets: List<Int> = listOf(18888, 29999, 8080, 5740)
)

class ConnectionViewModel(
    private val quickShareClient: IQuickShareClient,
    private val interfaceEnumerator: IInterfaceEnumerator,
    private val appConfigRepo: IAppConfigRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ConnectionUiState())
    val uiState: StateFlow<ConnectionUiState> = _uiState.asStateFlow()

    init {
        // Observe connection history
        viewModelScope.launch {
            appConfigRepo.connectionHistory.collect { history ->
                _uiState.update { state ->
                    val updated = state.copy(connectionHistory = history)
                    if (history.isNotEmpty() && state.targetIp.isEmpty()) {
                        val latest = history.first()
                        updated.copy(
                            targetIp = latest.ip,
                            targetPort = latest.port.toString()
                        )
                    } else {
                        updated
                    }
                }
            }
        }

        // Observe network interfaces
        viewModelScope.launch {
            interfaceEnumerator.observeInterfaces().collect { nics ->
                _uiState.update { state ->
                    val updatedNics = nics.map { nic ->
                        val existing = state.availableNics.find { it.name == nic.name }
                        nic.copy(isSelected = existing?.isSelected ?: true)
                    }
                    state.copy(availableNics = updatedNics)
                }
            }
        }

        // Observe client connection state
        viewModelScope.launch {
            quickShareClient.isConnected.collect { connected ->
                _uiState.update { state ->
                    if (connected) {
                        state.copy(
                            status = ClientConnectionStatus.CONNECTED,
                            isConnecting = false,
                            connectedIp = quickShareClient.connectedServerIp.value,
                            remoteFsName = if (quickShareClient.remoteFileSystem.value == QuickShareProtocolConstants.FILE_SYSTEM_WINDOWS) "Windows" else "Unix",
                            remoteHomeDir = quickShareClient.remoteHomeDir.value,
                            errorMessage = null
                        )
                    } else if (state.status == ClientConnectionStatus.CONNECTED) {
                        state.copy(
                            status = ClientConnectionStatus.DISCONNECTED,
                            isConnecting = false,
                            connectedIp = "",
                            remoteFsName = "",
                            remoteHomeDir = ""
                        )
                    } else {
                        state
                    }
                }
            }
        }
    }

    fun onIpChanged(ip: String) {
        _uiState.update {
            it.copy(
                targetIp = ip.trim(),
                ipError = validateIp(ip.trim()),
                errorMessage = null
            )
        }
    }

    fun onPortChanged(portStr: String) {
        _uiState.update {
            it.copy(
                targetPort = portStr.trim(),
                portError = validatePort(portStr.trim()),
                errorMessage = null
            )
        }
    }

    fun onPresetPortSelected(port: Int) {
        onPortChanged(port.toString())
    }

    fun onNicToggled(nicName: String, selected: Boolean) {
        _uiState.update { state ->
            val updated = state.availableNics.map {
                if (it.name == nicName) it.copy(isSelected = selected) else it
            }
            state.copy(availableNics = updated)
        }
    }

    fun onHistoryItemSelected(item: ConnectionHistoryItem) {
        _uiState.update {
            it.copy(
                targetIp = item.ip,
                targetPort = item.port.toString(),
                ipError = null,
                portError = null,
                errorMessage = null
            )
        }
    }

    fun onHistoryItemDeleted(item: ConnectionHistoryItem) {
        appConfigRepo.removeConnectionHistory(item)
    }

    fun clearHistory() {
        appConfigRepo.clearConnectionHistory()
    }

    fun connect() {
        val currentState = _uiState.value
        val ipErr = validateIp(currentState.targetIp)
        val portErr = validatePort(currentState.targetPort)

        if (ipErr != null || portErr != null) {
            _uiState.update { it.copy(ipError = ipErr, portError = portErr) }
            return
        }

        val port = currentState.targetPort.toInt()
        val selectedNics = currentState.availableNics.filter { it.isSelected }

        _uiState.update {
            it.copy(
                status = ClientConnectionStatus.CONNECTING,
                isConnecting = true,
                errorMessage = null
            )
        }

        viewModelScope.launch {
            val result = quickShareClient.connect(
                targetIp = currentState.targetIp,
                targetPort = port,
                selectedNics = selectedNics,
                timeoutMs = 5000,
                interfaceEnumerator = interfaceEnumerator
            )

            when (result) {
                is HandshakeResult.Success -> {
                    appConfigRepo.addConnectionHistory(currentState.targetIp, port)
                    _uiState.update {
                        it.copy(
                            status = ClientConnectionStatus.CONNECTED,
                            isConnecting = false,
                            connectedIp = currentState.targetIp,
                            remoteFsName = if (result.remoteFileSystem == QuickShareProtocolConstants.FILE_SYSTEM_WINDOWS) "Windows" else "Unix",
                            remoteHomeDir = result.remoteHomeDir,
                            errorMessage = null
                        )
                    }
                }
                is HandshakeResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            status = ClientConnectionStatus.ERROR,
                            isConnecting = false,
                            errorMessage = result.reason
                        )
                    }
                }
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            quickShareClient.disconnect()
            _uiState.update {
                it.copy(
                    status = ClientConnectionStatus.DISCONNECTED,
                    isConnecting = false,
                    connectedIp = "",
                    remoteFsName = "",
                    remoteHomeDir = "",
                    errorMessage = null
                )
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    private fun validateIp(ip: String): String? {
        if (ip.isBlank()) return "请输入目标 IP 地址"
        val ipv4Regex = Regex("""^(?:(?:25[0-5]|2[0-4]\d|[01]?\d\d?)\.){3}(?:25[0-5]|2[0-4]\d|[01]?\d\d?)$""")
        if (!ipv4Regex.matches(ip) && ip != "localhost" && ip != "127.0.0.1") {
            return "无效的 IPv4 地址格式"
        }
        return null
    }

    private fun validatePort(port: String): String? {
        if (port.isBlank()) return "请输入端口号"
        val p = port.toIntOrNull() ?: return "端口必须为数字"
        if (p !in 1..65535) return "端口范围需在 1~65535 之间"
        return null
    }
}
