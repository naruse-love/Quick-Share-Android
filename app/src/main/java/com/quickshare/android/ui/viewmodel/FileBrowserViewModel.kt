package com.quickshare.android.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quickshare.android.data.IAppConfigRepository
import com.quickshare.android.model.QuickShareDirectory
import com.quickshare.android.model.RemoteFile
import com.quickshare.android.network.IQuickShareClient
import com.quickshare.android.network.IQuickShareServer
import com.quickshare.android.protocol.QuickShareProtocolConstants
import com.quickshare.android.transfer.IStorageManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class BrowserTab {
    LOCAL,
    REMOTE
}

enum class SortField {
    NAME,
    SIZE,
    DATE
}

enum class SortOrder {
    ASCENDING,
    DESCENDING
}

data class BreadcrumbItem(
    val title: String,
    val fullPath: String
)

data class FileBrowserUiState(
    val activeTab: BrowserTab = BrowserTab.LOCAL,
    // Local explorer
    val currentLocalPath: String = "",
    val localBreadcrumbs: List<BreadcrumbItem> = emptyList(),
    val rawLocalFiles: List<RemoteFile> = emptyList(),
    val localFiles: List<RemoteFile> = emptyList(),
    val isLocalLoading: Boolean = false,
    val localError: String? = null,
    // Remote explorer
    val isRemoteConnected: Boolean = false,
    val currentRemotePath: String = "",
    val remoteBreadcrumbs: List<BreadcrumbItem> = emptyList(),
    val rawRemoteFiles: List<RemoteFile> = emptyList(),
    val remoteFiles: List<RemoteFile> = emptyList(),
    val isRemoteLoading: Boolean = false,
    val remoteError: String? = null,
    // Selection state
    val selectedFiles: Set<RemoteFile> = emptySet(),
    val isSelectionMode: Boolean = false,
    // Sorting and search
    val sortField: SortField = SortField.NAME,
    val sortOrder: SortOrder = SortOrder.ASCENDING,
    val searchQuery: String = "",
    // Transfer action feedback
    val isTransferring: Boolean = false,
    val transferMessage: String? = null
)

class FileBrowserViewModel(
    private val storageManager: IStorageManager,
    private val quickShareClient: IQuickShareClient,
    private val quickShareServer: IQuickShareServer,
    private val appConfigRepo: IAppConfigRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val _uiState = MutableStateFlow(FileBrowserUiState())
    val uiState: StateFlow<FileBrowserUiState> = _uiState.asStateFlow()

    init {
        // Initialize default local path from AppConfig
        val initialLocal = appConfigRepo.appConfig.value.saveDirectory
        _uiState.update {
            it.copy(
                currentLocalPath = initialLocal,
                localBreadcrumbs = generateBreadcrumbs(initialLocal, QuickShareProtocolConstants.FILE_SYSTEM_UNIX)
            )
        }
        loadLocalFiles(initialLocal)

        // Observe client connection state for remote tab
        viewModelScope.launch {
            quickShareClient.isConnected.collect { connected ->
                _uiState.update { it.copy(isRemoteConnected = connected) }
                if (connected && _uiState.value.currentRemotePath.isEmpty()) {
                    val remoteHome = quickShareClient.remoteHomeDir.value.ifEmpty { "C:\\" }
                    val remoteFs = quickShareClient.remoteFileSystem.value
                    _uiState.update {
                        it.copy(
                            currentRemotePath = remoteHome,
                            remoteBreadcrumbs = generateBreadcrumbs(remoteHome, remoteFs)
                        )
                    }
                    loadRemoteFiles(remoteHome)
                }
            }
        }
    }

    fun switchTab(tab: BrowserTab) {
        _uiState.update {
            it.copy(
                activeTab = tab,
                selectedFiles = emptySet(),
                isSelectionMode = false,
                searchQuery = ""
            )
        }
        if (tab == BrowserTab.LOCAL) {
            loadLocalFiles(_uiState.value.currentLocalPath)
        } else if (_uiState.value.isRemoteConnected) {
            loadRemoteFiles(_uiState.value.currentRemotePath)
        }
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.update { state ->
            val updated = state.copy(searchQuery = query)
            updated.copy(
                localFiles = sortAndFilterFiles(updated.rawLocalFiles, updated.sortField, updated.sortOrder, query),
                remoteFiles = sortAndFilterFiles(updated.rawRemoteFiles, updated.sortField, updated.sortOrder, query)
            )
        }
    }

    fun onSortChanged(field: SortField, order: SortOrder) {
        _uiState.update { state ->
            val updated = state.copy(sortField = field, sortOrder = order)
            updated.copy(
                localFiles = sortAndFilterFiles(updated.rawLocalFiles, field, order, updated.searchQuery),
                remoteFiles = sortAndFilterFiles(updated.rawRemoteFiles, field, order, updated.searchQuery)
            )
        }
    }

    // --- File Item Interaction ---

    fun onFileClicked(file: RemoteFile) {
        val state = _uiState.value
        if (state.isSelectionMode) {
            toggleFileSelection(file)
            return
        }

        if (file.isDirectory) {
            if (state.activeTab == BrowserTab.LOCAL) {
                navigateToLocal(file.path)
            } else {
                navigateToRemote(file.path)
            }
        } else {
            toggleFileSelection(file)
        }
    }

    fun onFileLongClicked(file: RemoteFile) {
        toggleFileSelection(file)
    }

    fun toggleFileSelection(file: RemoteFile) {
        _uiState.update { state ->
            val current = state.selectedFiles.toMutableSet()
            if (current.contains(file)) {
                current.remove(file)
            } else {
                current.add(file)
            }
            state.copy(
                selectedFiles = current,
                isSelectionMode = current.isNotEmpty()
            )
        }
    }

    fun selectAll() {
        val state = _uiState.value
        val allFiles = if (state.activeTab == BrowserTab.LOCAL) state.localFiles else state.remoteFiles
        _uiState.update {
            it.copy(
                selectedFiles = allFiles.toSet(),
                isSelectionMode = allFiles.isNotEmpty()
            )
        }
    }

    fun clearSelection() {
        _uiState.update {
            it.copy(
                selectedFiles = emptySet(),
                isSelectionMode = false
            )
        }
    }

    // --- Local Explorer Navigation ---

    fun navigateToLocal(path: String) {
        _uiState.update {
            it.copy(
                currentLocalPath = path,
                localBreadcrumbs = generateBreadcrumbs(path, QuickShareProtocolConstants.FILE_SYSTEM_UNIX),
                selectedFiles = emptySet(),
                isSelectionMode = false
            )
        }
        loadLocalFiles(path)
    }

    fun navigateUpLocal() {
        val current = _uiState.value.currentLocalPath
        val parent = QuickShareDirectory(current, QuickShareDirectory.FILE_SYSTEM_UNIX).parent()
        if (parent != null) {
            val parentPath = if (parent.path.length > 1 && parent.path.endsWith("/")) {
                parent.path.substring(0, parent.path.length - 1)
            } else {
                parent.path
            }
            navigateToLocal(parentPath)
        }
    }

    fun refreshCurrentDirectory() {
        if (_uiState.value.activeTab == BrowserTab.LOCAL) {
            loadLocalFiles(_uiState.value.currentLocalPath)
        } else {
            loadRemoteFiles(_uiState.value.currentRemotePath)
        }
    }

    private fun loadLocalFiles(path: String) {
        _uiState.update { it.copy(isLocalLoading = true, localError = null) }
        viewModelScope.launch(ioDispatcher) {
            try {
                val files = storageManager.listFiles(path)
                _uiState.update { state ->
                    state.copy(
                        rawLocalFiles = files,
                        localFiles = sortAndFilterFiles(files, state.sortField, state.sortOrder, state.searchQuery),
                        isLocalLoading = false,
                        localError = null
                    )
                }
            } catch (e: Throwable) {
                _uiState.update {
                    it.copy(
                        isLocalLoading = false,
                        localError = "无法读取本地目录: ${e.message}"
                    )
                }
            }
        }
    }

    // --- Remote Explorer Navigation ---

    fun navigateToRemote(path: String) {
        val fs = quickShareClient.remoteFileSystem.value
        _uiState.update {
            it.copy(
                currentRemotePath = path,
                remoteBreadcrumbs = generateBreadcrumbs(path, fs),
                selectedFiles = emptySet(),
                isSelectionMode = false
            )
        }
        loadRemoteFiles(path)
    }

    fun navigateUpRemote() {
        val current = _uiState.value.currentRemotePath
        val isWindows = quickShareClient.remoteFileSystem.value == QuickShareProtocolConstants.FILE_SYSTEM_WINDOWS
        val quickShareFs = if (isWindows) QuickShareDirectory.FILE_SYSTEM_WINDOWS else QuickShareDirectory.FILE_SYSTEM_UNIX
        val parent = QuickShareDirectory(current, quickShareFs).parent()
        if (parent != null) {
            val parentPath = if (parent.path.length > 1 && (parent.path.endsWith("/") || parent.path.endsWith("\\"))) {
                parent.path.substring(0, parent.path.length - 1)
            } else {
                parent.path
            }
            navigateToRemote(parentPath)
        }
    }

    private fun loadRemoteFiles(path: String) {
        if (!_uiState.value.isRemoteConnected) return
        _uiState.update { it.copy(isRemoteLoading = true, remoteError = null) }
        viewModelScope.launch(ioDispatcher) {
            val files = quickShareClient.listRemoteFiles(path)
            if (files != null) {
                _uiState.update { state ->
                    state.copy(
                        rawRemoteFiles = files,
                        remoteFiles = sortAndFilterFiles(files, state.sortField, state.sortOrder, state.searchQuery),
                        isRemoteLoading = false,
                        remoteError = null
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        isRemoteLoading = false,
                        remoteError = "无法读取远程目录: $path"
                    )
                }
            }
        }
    }

    // --- Directory and File Operations ---

    fun createDirectory(name: String) {
        if (name.isBlank()) return
        val state = _uiState.value
        viewModelScope.launch(ioDispatcher) {
            if (state.activeTab == BrowserTab.LOCAL) {
                val ok = storageManager.mkdir(state.currentLocalPath, name)
                if (ok) loadLocalFiles(state.currentLocalPath)
            } else {
                val ok = quickShareClient.makeRemoteDir(state.currentRemotePath, name)
                if (ok) loadRemoteFiles(state.currentRemotePath)
            }
        }
    }

    fun deleteSelectedFiles() {
        val state = _uiState.value
        val targets = state.selectedFiles.toList()
        if (targets.isEmpty()) return

        viewModelScope.launch(ioDispatcher) {
            if (state.activeTab == BrowserTab.LOCAL) {
                for (file in targets) {
                    storageManager.delete(file.path)
                }
                clearSelection()
                loadLocalFiles(state.currentLocalPath)
            } else {
                for (file in targets) {
                    quickShareClient.deleteRemoteFile(file.path)
                }
                clearSelection()
                loadRemoteFiles(state.currentRemotePath)
            }
        }
    }

    // --- Transfer Dispatch ---

    fun transferSelectedFiles(onTransferStarted: () -> Unit = {}) {
        val state = _uiState.value
        val targets = state.selectedFiles.toList()
        if (targets.isEmpty()) return

        _uiState.update { it.copy(isTransferring = true) }
        onTransferStarted()

        viewModelScope.launch {
            if (state.activeTab == BrowserTab.LOCAL) {
                // Upload local selected files to remote current folder
                val remoteDest = state.currentRemotePath.ifEmpty { quickShareClient.remoteHomeDir.value }
                val paths = targets.map { it.path }
                val success = quickShareClient.sendFiles(paths, remoteDest)
                _uiState.update {
                    it.copy(
                        isTransferring = false,
                        transferMessage = if (success) "发送完成" else "发送失败"
                    )
                }
            } else {
                // Download remote selected files to local current folder
                val localDest = state.currentLocalPath.ifEmpty { appConfigRepo.appConfig.value.saveDirectory }
                val paths = targets.map { it.path }
                val success = quickShareClient.receiveFiles(paths, state.currentRemotePath, localDest)
                _uiState.update {
                    it.copy(
                        isTransferring = false,
                        transferMessage = if (success) "下载完成" else "下载失败"
                    )
                }
                loadLocalFiles(localDest)
            }
            clearSelection()
        }
    }

    private fun sortAndFilterFiles(
        files: List<RemoteFile>,
        field: SortField,
        order: SortOrder,
        query: String
    ): List<RemoteFile> {
        val filtered = if (query.isBlank()) {
            files
        } else {
            files.filter { it.name.contains(query, ignoreCase = true) }
        }

        val comparator = when (field) {
            SortField.NAME -> compareBy<RemoteFile> { !it.isDirectory }.thenBy { it.name.lowercase() }
            SortField.SIZE -> compareBy<RemoteFile> { !it.isDirectory }.thenBy { it.size }
            SortField.DATE -> compareBy<RemoteFile> { !it.isDirectory }.thenBy { it.lastModified }
        }

        return if (order == SortOrder.ASCENDING) {
            filtered.sortedWith(comparator)
        } else {
            // Folders remain on top when sorted descending
            val dirs = filtered.filter { it.isDirectory }.sortedWith(comparator).reversed()
            val fileItems = filtered.filter { !it.isDirectory }.sortedWith(comparator).reversed()
            dirs + fileItems
        }
    }

    companion object {
        fun generateBreadcrumbs(path: String, fileSystem: Int): List<BreadcrumbItem> {
            if (path.isBlank() || path == "/") {
                return listOf(BreadcrumbItem("根目录", "/"))
            }

            val items = mutableListOf<BreadcrumbItem>()
            val separator = if (fileSystem == QuickShareProtocolConstants.FILE_SYSTEM_WINDOWS) "\\" else "/"
            val normalized = path.replace('/', separator[0]).replace('\\', separator[0])

            val segments = normalized.split(separator).filter { it.isNotEmpty() }
            var accumulated = if (fileSystem == QuickShareProtocolConstants.FILE_SYSTEM_WINDOWS) "" else "/"

            if (fileSystem == QuickShareProtocolConstants.FILE_SYSTEM_UNIX) {
                items.add(BreadcrumbItem("根目录", "/"))
            }

            for (segment in segments) {
                accumulated = if (accumulated.endsWith(separator) || accumulated.isEmpty()) {
                    accumulated + segment
                } else {
                    accumulated + separator + segment
                }
                items.add(BreadcrumbItem(segment, accumulated))
            }
            return items
        }
    }
}
