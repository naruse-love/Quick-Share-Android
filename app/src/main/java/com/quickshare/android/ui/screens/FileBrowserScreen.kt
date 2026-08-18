package com.quickshare.android.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.quickshare.android.model.RemoteFile
import com.quickshare.android.network.TrafficManager
import com.quickshare.android.ui.components.CreateFolderDialog
import com.quickshare.android.ui.components.DeleteConfirmDialog
import com.quickshare.android.ui.components.ErrorDialog
import com.quickshare.android.ui.theme.MonoDetail
import com.quickshare.android.ui.viewmodel.BrowserTab
import com.quickshare.android.ui.viewmodel.FileBrowserViewModel
import com.quickshare.android.ui.viewmodel.SortField
import com.quickshare.android.ui.viewmodel.SortOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun FileBrowserScreen(
    viewModel: FileBrowserViewModel,
    onNavigateToDashboard: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showCreateDirDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    if (showCreateDirDialog) {
        CreateFolderDialog(
            onDismiss = { showCreateDirDialog = false },
            onConfirm = { name ->
                viewModel.createDirectory(name)
                showCreateDirDialog = false
            }
        )
    }

    if (showDeleteConfirmDialog) {
        DeleteConfirmDialog(
            itemCount = uiState.selectedFiles.size,
            onDismiss = { showDeleteConfirmDialog = false },
            onConfirm = {
                viewModel.deleteSelectedFiles()
                showDeleteConfirmDialog = false
            }
        )
    }

    val currentError = if (uiState.activeTab == BrowserTab.LOCAL) uiState.localError else uiState.remoteError
    if (currentError != null) {
        ErrorDialog(
            title = "文件系统提示",
            message = currentError,
            onDismiss = { viewModel.refreshCurrentDirectory() }
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        // 1. Dual-Tab Header
        TabRow(
            selectedTabIndex = if (uiState.activeTab == BrowserTab.LOCAL) 0 else 1
        ) {
            Tab(
                selected = uiState.activeTab == BrowserTab.LOCAL,
                onClick = { viewModel.switchTab(BrowserTab.LOCAL) },
                text = { Text("本地文件 (Local)") }
            )
            Tab(
                selected = uiState.activeTab == BrowserTab.REMOTE,
                onClick = { viewModel.switchTab(BrowserTab.REMOTE) },
                text = { Text("远端文件 (Remote)") }
            )
        }

        // 2. Breadcrumb Path Bar & Up Button
        BreadcrumbBar(
            breadcrumbs = if (uiState.activeTab == BrowserTab.LOCAL) uiState.localBreadcrumbs else uiState.remoteBreadcrumbs,
            onNavigate = { path ->
                if (uiState.activeTab == BrowserTab.LOCAL) {
                    viewModel.navigateToLocal(path)
                } else {
                    viewModel.navigateToRemote(path)
                }
            },
            onNavigateUp = {
                if (uiState.activeTab == BrowserTab.LOCAL) {
                    viewModel.navigateUpLocal()
                } else {
                    viewModel.navigateUpRemote()
                }
            },
            onRefresh = { viewModel.refreshCurrentDirectory() }
        )

        // 3. Search and Sort Filter Toolbar
        SearchAndSortBar(
            searchQuery = uiState.searchQuery,
            onSearchQueryChanged = { viewModel.onSearchQueryChanged(it) },
            sortField = uiState.sortField,
            sortOrder = uiState.sortOrder,
            onSortChanged = { field, order -> viewModel.onSortChanged(field, order) }
        )

        // 4. File List Body
        Box(modifier = Modifier.weight(1f)) {
            val isLoading = if (uiState.activeTab == BrowserTab.LOCAL) uiState.isLocalLoading else uiState.isRemoteLoading
            val currentFiles = if (uiState.activeTab == BrowserTab.LOCAL) uiState.localFiles else uiState.remoteFiles

            if (uiState.activeTab == BrowserTab.REMOTE && !uiState.isRemoteConnected) {
                // Not Connected Placeholder
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "未连接到远程服务端",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "请前往「客户端连接」页面配置并连接到 PC 端服务器",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (isLoading) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    CircularProgressIndicator()
                }
            } else if (currentFiles.isEmpty()) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Text(
                        text = "当前目录下无文件",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(currentFiles, key = { it.path }) { file ->
                        val isSelected = uiState.selectedFiles.contains(file)
                        FileListItemRow(
                            file = file,
                            isSelected = isSelected,
                            isSelectionMode = uiState.isSelectionMode,
                            onClick = { viewModel.onFileClicked(file) },
                            onLongClick = { viewModel.onFileLongClicked(file) },
                            onCheckboxClick = { viewModel.toggleFileSelection(file) }
                        )
                    }
                }
            }
        }

        // 5. Contextual Batch Action Bar
        if (uiState.isSelectionMode) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shadowElevation = 8.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "已选中 ${uiState.selectedFiles.size} 项",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(onClick = { viewModel.selectAll() }) {
                            Icon(Icons.Default.SelectAll, contentDescription = "Select All")
                        }

                        IconButton(onClick = { showCreateDirDialog = true }) {
                            Icon(Icons.Default.CreateNewFolder, contentDescription = "New Folder")
                        }

                        IconButton(onClick = { showDeleteConfirmDialog = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                        }

                        Button(
                            onClick = {
                                viewModel.transferSelectedFiles {
                                    onNavigateToDashboard()
                                }
                            }
                        ) {
                            if (uiState.activeTab == BrowserTab.LOCAL) {
                                Icon(Icons.Default.Upload, contentDescription = null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("发送到远端")
                            } else {
                                Icon(Icons.Default.Download, contentDescription = null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("下载到本地")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BreadcrumbBar(
    breadcrumbs: List<com.quickshare.android.ui.viewmodel.BreadcrumbItem>,
    onNavigate: (String) -> Unit,
    onNavigateUp: () -> Unit,
    onRefresh: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            IconButton(onClick = onNavigateUp, modifier = Modifier.size(36.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Up")
            }

            IconButton(onClick = onRefresh, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh")
            }

            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                breadcrumbs.forEachIndexed { index, item ->
                    AssistChip(
                        onClick = { onNavigate(item.fullPath) },
                        label = { Text(item.title, fontSize = 13.sp) },
                        trailingIcon = if (index < breadcrumbs.size - 1) {
                            { Text(">", color = MaterialTheme.colorScheme.outline) }
                        } else null
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchAndSortBar(
    searchQuery: String,
    onSearchQueryChanged: (String) -> Unit,
    sortField: SortField,
    sortOrder: SortOrder,
    onSortChanged: (SortField, SortOrder) -> Unit
) {
    var sortMenuExpanded by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChanged,
            placeholder = { Text("搜索文件名...", fontSize = 14.sp) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
            trailingIcon = if (searchQuery.isNotEmpty()) {
                {
                    IconButton(onClick = { onSearchQueryChanged("") }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear")
                    }
                }
            } else null,
            singleLine = true,
            modifier = Modifier
                .weight(1f)
                .height(50.dp)
        )

        Spacer(modifier = Modifier.width(8.dp))

        Box {
            IconButton(onClick = { sortMenuExpanded = true }) {
                Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort")
            }

            DropdownMenu(
                expanded = sortMenuExpanded,
                onDismissRequest = { sortMenuExpanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text("按名称排序") },
                    onClick = {
                        onSortChanged(SortField.NAME, if (sortField == SortField.NAME && sortOrder == SortOrder.ASCENDING) SortOrder.DESCENDING else SortOrder.ASCENDING)
                        sortMenuExpanded = false
                    }
                )
                DropdownMenuItem(
                    text = { Text("按大小排序") },
                    onClick = {
                        onSortChanged(SortField.SIZE, if (sortField == SortField.SIZE && sortOrder == SortOrder.ASCENDING) SortOrder.DESCENDING else SortOrder.ASCENDING)
                        sortMenuExpanded = false
                    }
                )
                DropdownMenuItem(
                    text = { Text("按修改日期排序") },
                    onClick = {
                        onSortChanged(SortField.DATE, if (sortField == SortField.DATE && sortOrder == SortOrder.ASCENDING) SortOrder.DESCENDING else SortOrder.ASCENDING)
                        sortMenuExpanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileListItemRow(
    file: RemoteFile,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onCheckboxClick: () -> Unit
) {
    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    val formattedDate = if (file.lastModified > 0) dateFormat.format(Date(file.lastModified)) else ""
    val formattedSize = if (file.isDirectory) "文件夹" else TrafficManager.formatSize(file.size)

    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            else MaterialTheme.colorScheme.surface
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Icon(
                imageVector = if (file.isDirectory) Icons.Default.Folder else Icons.Default.Description,
                contentDescription = null,
                tint = if (file.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(28.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = formattedSize,
                        style = MonoDetail,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (formattedDate.isNotEmpty()) {
                        Text(
                            text = formattedDate,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Checkbox(
                checked = isSelected,
                onCheckedChange = { onCheckboxClick() }
            )
        }
    }
}
