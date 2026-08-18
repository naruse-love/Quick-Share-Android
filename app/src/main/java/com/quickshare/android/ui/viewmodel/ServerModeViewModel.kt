package com.quickshare.android.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quickshare.android.data.IAppConfigRepository
import com.quickshare.android.model.NetworkInterfaceInfo
import com.quickshare.android.network.ClientSessionInfo
import com.quickshare.android.network.IQuickShareServer
import com.quickshare.android.network.IInterfaceEnumerator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ServerRunningStatus {
    STOPPED,
    STARTING,
    RUNNING,
    ERROR
}

data class ServerModeUiState(
    val listenPort: String = "18888",
    val portError: String? = null,
    val status: ServerRunningStatus = ServerRunningStatus.STOPPED,
    val isRunning: Boolean = false,
    val statusText: String = "服务未启动",
    val activeNics: List<NetworkInterfaceInfo> = emptyList(),
    val connectedClients: List<ClientSessionInfo> = emptyList(),
    val errorMessage: String? = null,
    val portPresets: List<Int> = listOf(18888, 29999, 8080, 5740)
)

class ServerModeViewModel(
    private val quickShareServer: IQuickShareServer,
    private val interfaceEnumerator: IInterfaceEnumerator,
    private val appConfigRepo: IAppConfigRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ServerModeUiState())
    val uiState: StateFlow<ServerModeUiState> = _uiState.asStateFlow()

    init {
        // Initialize port from AppConfig
        val savedPort = appConfigRepo.appConfig.value.port
        if (savedPort in 1..65535) {
            _uiState.update { it.copy(listenPort = savedPort.toString()) }
        }

        // Observe network interfaces for IP broadcast
        viewModelScope.launch {
            interfaceEnumerator.observeInterfaces().collect { nics ->
                _uiState.update { state ->
                    val updatedNics = nics.map { nic ->
                        val existing = state.activeNics.find { it.name == nic.name }
                        nic.copy(isSelected = existing?.isSelected ?: true)
                    }
                    state.copy(activeNics = updatedNics)
                }
            }
        }

        // Observe server running state
        viewModelScope.launch {
            quickShareServer.isRunning.collect { running ->
                _uiState.update {
                    it.copy(
                        isRunning = running,
                        status = if (running) ServerRunningStatus.RUNNING else ServerRunningStatus.STOPPED
                    )
                }
            }
        }

        // Observe server status text
        viewModelScope.launch {
            quickShareServer.serverStatusText.collect { text ->
                _uiState.update { it.copy(statusText = text) }
            }
        }

        // Observe connected clients
        viewModelScope.launch {
            quickShareServer.connectedClients.collect { clients ->
                _uiState.update { it.copy(connectedClients = clients) }
            }
        }
    }

    fun onPortChanged(portStr: String) {
        _uiState.update {
            it.copy(
                listenPort = portStr.trim(),
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
            val updated = state.activeNics.map {
                if (it.name == nicName) it.copy(isSelected = selected) else it
            }
            state.copy(activeNics = updated)
        }
    }

    fun toggleServer() {
        if (_uiState.value.isRunning) {
            stopServer()
        } else {
            startServer()
        }
    }

    fun startServer() {
        val currentState = _uiState.value
        val portErr = validatePort(currentState.listenPort)
        if (portErr != null) {
            _uiState.update { it.copy(portError = portErr) }
            return
        }

        val port = currentState.listenPort.toInt()
        val selectedNics = currentState.activeNics.filter { it.isSelected }

        _uiState.update {
            it.copy(
                status = ServerRunningStatus.STARTING,
                errorMessage = null
            )
        }

        viewModelScope.launch {
            val started = quickShareServer.start(
                listenPort = port,
                activeNics = selectedNics,
                bindAddress = "0.0.0.0"
            )
            if (started) {
                appConfigRepo.updateConfig { it.copy(port = port) }
                _uiState.update {
                    it.copy(
                        status = ServerRunningStatus.RUNNING,
                        isRunning = true,
                        errorMessage = null
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        status = ServerRunningStatus.ERROR,
                        isRunning = false,
                        errorMessage = "启动服务端失败，请检查端口 $port 是否已被占用"
                    )
                }
            }
        }
    }

    fun stopServer() {
        viewModelScope.launch {
            quickShareServer.stop()
            _uiState.update {
                it.copy(
                    status = ServerRunningStatus.STOPPED,
                    isRunning = false,
                    errorMessage = null
                )
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    private fun validatePort(port: String): String? {
        if (port.isBlank()) return "请输入监听端口"
        val p = port.toIntOrNull() ?: return "端口必须为数字"
        if (p !in 1..65535) return "端口范围需在 1~65535 之间"
        return null
    }
}
