package com.quickshare.android.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quickshare.android.data.IAppConfigRepository
import com.quickshare.android.model.AppConfig
import com.quickshare.android.model.TransferStatus
import com.quickshare.android.network.IQuickShareClient
import com.quickshare.android.network.IQuickShareServer
import com.quickshare.android.transfer.IStorageManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AppTab {
    CONNECTION,
    SERVER_MODE,
    FILE_BROWSER,
    DASHBOARD
}

sealed class UiEvent {
    data class ShowSnackbar(
        val message: String,
        val actionLabel: String? = null,
        val onAction: (() -> Unit)? = null
    ) : UiEvent()

    data class ShowToast(val message: String) : UiEvent()
    data class ShowErrorDialog(val title: String, val message: String) : UiEvent()
    data class NavigateToTab(val tab: AppTab) : UiEvent()
    data object RequestStoragePermission : UiEvent()
}

data class MainUiState(
    val currentTab: AppTab = AppTab.CONNECTION,
    val isClientConnected: Boolean = false,
    val isServerRunning: Boolean = false,
    val activeTransferBadgeCount: Int = 0,
    val isStoragePermissionGranted: Boolean = true,
    val appConfig: AppConfig = AppConfig()
)

class MainViewModel(
    private val quickShareClient: IQuickShareClient,
    private val quickShareServer: IQuickShareServer,
    private val storageManager: IStorageManager,
    private val appConfigRepo: IAppConfigRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val _uiEvents = MutableSharedFlow<UiEvent>(extraBufferCapacity = 16)
    val uiEvents: SharedFlow<UiEvent> = _uiEvents.asSharedFlow()

    init {
        // Observe app config
        viewModelScope.launch {
            appConfigRepo.appConfig.collect { config ->
                _uiState.update { it.copy(appConfig = config) }
            }
        }

        // Observe client connection state
        viewModelScope.launch {
            quickShareClient.isConnected.collect { connected ->
                _uiState.update { it.copy(isClientConnected = connected) }
            }
        }

        // Observe server running state
        viewModelScope.launch {
            quickShareServer.isRunning.collect { running ->
                _uiState.update { it.copy(isServerRunning = running) }
            }
        }

        // Combine active client & server transfers for badge counter
        viewModelScope.launch {
            combine(quickShareClient.currentTask, quickShareServer.activeTransfers) { clientTask, serverTasks ->
                var count = 0
                if (clientTask != null && clientTask.status == TransferStatus.RUNNING) {
                    count++
                }
                count += serverTasks.count { it.status == TransferStatus.RUNNING }
                count
            }.collect { badgeCount ->
                _uiState.update { it.copy(activeTransferBadgeCount = badgeCount) }
            }
        }

        // Check initial storage permission
        checkStoragePermission()
    }

    fun selectTab(tab: AppTab) {
        _uiState.update { it.copy(currentTab = tab) }
    }

    fun checkStoragePermission() {
        val granted = storageManager.isDirectAccessAvailable()
        _uiState.update { it.copy(isStoragePermissionGranted = granted) }
        if (!granted) {
            emitEvent(UiEvent.RequestStoragePermission)
        }
    }

    fun onStoragePermissionResult(granted: Boolean) {
        _uiState.update { it.copy(isStoragePermissionGranted = granted) }
    }

    fun emitEvent(event: UiEvent) {
        _uiEvents.tryEmit(event)
    }

    fun updateSaveDirectory(dirPath: String) {
        appConfigRepo.updateConfig { it.copy(saveDirectory = dirPath) }
        emitEvent(UiEvent.ShowToast("保存目录已更新: $dirPath"))
    }

    fun updateBufferCount(count: Int) {
        appConfigRepo.updateConfig { it.copy(bufferCount = count) }
    }

    fun updateKeepScreenOn(enabled: Boolean) {
        appConfigRepo.updateConfig { it.copy(keepScreenOn = enabled) }
    }
}
