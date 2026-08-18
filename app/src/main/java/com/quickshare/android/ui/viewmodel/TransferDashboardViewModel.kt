package com.quickshare.android.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quickshare.android.model.TransferStatus
import com.quickshare.android.model.TransferTask
import com.quickshare.android.network.ChannelTrafficSnapshot
import com.quickshare.android.network.IQuickShareClient
import com.quickshare.android.network.IQuickShareServer
import com.quickshare.android.network.ITrafficManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TransferDashboardUiState(
    val activeTask: TransferTask? = null,
    val completedTasks: List<TransferTask> = emptyList(),
    val failedTasks: List<TransferTask> = emptyList(),
    // Real-time Traffic snapshot from TrafficManager
    val totalSpeedFormatted: String = "0 B/s",
    val totalTransferredFormatted: String = "0 B",
    val totalSizeFormatted: String = "0 B",
    val progressPercent: Double = 0.0,
    val etaFormatted: String = "--",
    val channelSnapshots: List<ChannelTrafficSnapshot> = emptyList(),
    val isTransferActive: Boolean = false
)

class TransferDashboardViewModel(
    private val trafficManager: ITrafficManager,
    private val quickShareClient: IQuickShareClient,
    private val quickShareServer: IQuickShareServer
) : ViewModel() {

    private val _uiState = MutableStateFlow(TransferDashboardUiState())
    val uiState: StateFlow<TransferDashboardUiState> = _uiState.asStateFlow()

    init {
        // Observe real-time 1s window traffic snapshot from TrafficManager
        viewModelScope.launch {
            trafficManager.trafficState.collect { snapshot ->
                _uiState.update { state ->
                    state.copy(
                        totalSpeedFormatted = snapshot.formattedSpeed,
                        totalTransferredFormatted = snapshot.formattedTransferred,
                        totalSizeFormatted = snapshot.formattedTotalSize,
                        progressPercent = snapshot.progressPercent,
                        etaFormatted = snapshot.formattedEta,
                        channelSnapshots = snapshot.channelSnapshots,
                        isTransferActive = snapshot.progressPercent > 0.0 && snapshot.progressPercent < 100.0
                    )
                }
            }
        }

        // Observe client active transfer task
        viewModelScope.launch {
            quickShareClient.currentTask.collect { task ->
                handleTaskUpdate(task)
            }
        }

        // Observe server active transfer tasks
        viewModelScope.launch {
            quickShareServer.activeTransfers.collect { tasks ->
                val primary = tasks.firstOrNull()
                handleTaskUpdate(primary)
            }
        }
    }

    private fun handleTaskUpdate(task: TransferTask?) {
        if (task == null) {
            _uiState.update { it.copy(activeTask = null, isTransferActive = false) }
            return
        }

        _uiState.update { state ->
            when (task.status) {
                TransferStatus.RUNNING -> {
                    state.copy(activeTask = task, isTransferActive = true)
                }
                TransferStatus.COMPLETED -> {
                    val completed = state.completedTasks.toMutableList()
                    if (completed.none { it.id == task.id }) {
                        completed.add(0, task)
                    }
                    state.copy(activeTask = null, completedTasks = completed, isTransferActive = false)
                }
                TransferStatus.FAILED, TransferStatus.CANCELLED -> {
                    val failed = state.failedTasks.toMutableList()
                    if (failed.none { it.id == task.id }) {
                        failed.add(0, task)
                    }
                    state.copy(activeTask = null, failedTasks = failed, isTransferActive = false)
                }
                else -> state.copy(activeTask = task)
            }
        }
    }

    fun cancelActiveTransfer() {
        trafficManager.stopMonitoring()

        val current = _uiState.value.activeTask
        if (current != null) {
            val cancelledTask = current.withStatus(TransferStatus.CANCELLED, "用户已取消传输")
            _uiState.update { state ->
                val failed = state.failedTasks.toMutableList()
                if (failed.none { it.id == current.id }) {
                    failed.add(0, cancelledTask)
                }
                state.copy(activeTask = null, failedTasks = failed, isTransferActive = false)
            }
        } else {
            _uiState.update { it.copy(isTransferActive = false) }
        }

        viewModelScope.launch {
            try {
                quickShareClient.disconnect()
            } catch (_: Throwable) {}
        }
    }

    fun clearTaskHistory() {
        _uiState.update {
            it.copy(
                completedTasks = emptyList(),
                failedTasks = emptyList()
            )
        }
    }
}
