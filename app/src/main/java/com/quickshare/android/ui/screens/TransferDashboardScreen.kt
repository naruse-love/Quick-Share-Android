package com.quickshare.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.quickshare.android.model.TransferDirection
import com.quickshare.android.model.TransferStatus
import com.quickshare.android.model.TransferTask
import com.quickshare.android.network.TrafficManager
import com.quickshare.android.ui.components.SpeedGaugeCard
import com.quickshare.android.ui.components.TransferStatusBadge
import com.quickshare.android.ui.theme.MonoDetail
import com.quickshare.android.ui.viewmodel.TransferDashboardViewModel

@Composable
fun TransferDashboardScreen(
    viewModel: TransferDashboardViewModel,
    onNavigateToBrowser: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Real-time Aggregated Speed & ETA Hero Card
        item {
            SpeedGaugeCard(
                speedText = uiState.totalSpeedFormatted,
                transferredText = uiState.totalTransferredFormatted,
                totalSizeText = uiState.totalSizeFormatted,
                progressPercent = uiState.progressPercent,
                etaText = uiState.etaFormatted
            )
        }

        // 2. Active Transfer Task Card
        if (uiState.activeTask != null) {
            val task = uiState.activeTask!!
            item {
                ElevatedCard(
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (task.direction == TransferDirection.SEND) Icons.Default.Upload else Icons.Default.Download,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (task.direction == TransferDirection.SEND) "正在发送" else "正在接收",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            TransferStatusBadge(status = task.status)
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = task.fileName,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = "${TrafficManager.formatSize(task.bytesTransferred)} / ${TrafficManager.formatSize(task.size)} (${String.format("%.1f", task.progress)}%)",
                            style = MonoDetail,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = { viewModel.cancelActiveTransfer() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Cancel, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("取消当前传输")
                        }
                    }
                }
            }
        }

        // 3. Task History / Completed & Failed Tasks
        if (uiState.completedTasks.isNotEmpty() || uiState.failedTasks.isNotEmpty()) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "传输记录",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(onClick = { viewModel.clearTaskHistory() }) {
                        Text("清空记录")
                    }
                }
            }

            items(uiState.completedTasks, key = { "completed_${it.id}" }) { task ->
                TaskHistoryRow(task = task)
            }

            items(uiState.failedTasks, key = { "failed_${it.id}" }) { task ->
                TaskHistoryRow(task = task)
            }
        } else if (uiState.activeTask == null) {
            item {
                ElevatedCard(
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "当前无进行中的传输任务",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "可在文件浏览中选择文件并发送/下载",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = onNavigateToBrowser,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Folder, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("前往文件浏览")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskHistoryRow(task: TransferTask) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clickable {
                val path = if (task.filePath.isNotEmpty()) {
                    task.filePath
                } else {
                    val downloadDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                    val candidate = java.io.File(downloadDir, task.fileName)
                    if (candidate.exists()) candidate.absolutePath else java.io.File("/sdcard/Download", task.fileName).absolutePath
                }
                com.quickshare.android.util.FileOpener.openFile(context, path)
            }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Icon(
                imageVector = if (task.status == TransferStatus.COMPLETED)
                    Icons.Default.CheckCircle
                else Icons.Default.Error,
                contentDescription = null,
                tint = if (task.status == TransferStatus.COMPLETED)
                    MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (task.direction == TransferDirection.SEND) "发送" else "接收",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = TrafficManager.formatSize(task.size),
                        style = MonoDetail,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            TransferStatusBadge(status = task.status)
        }
    }
}
