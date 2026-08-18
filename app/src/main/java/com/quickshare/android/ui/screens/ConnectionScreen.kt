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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.quickshare.android.data.ConnectionHistoryItem
import com.quickshare.android.ui.components.ClientConnectionStatusBadge
import com.quickshare.android.ui.components.ErrorDialog
import com.quickshare.android.ui.viewmodel.ClientConnectionStatus
import com.quickshare.android.ui.viewmodel.ConnectionViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ConnectionScreen(
    viewModel: ConnectionViewModel,
    onNavigateToFiles: () -> Unit = {},
    onNavigateToDashboard: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    if (uiState.errorMessage != null) {
        ErrorDialog(
            title = "连接失败",
            message = uiState.errorMessage ?: "",
            onDismiss = { viewModel.clearError() }
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Status Header Card
        item {
            ElevatedCard(
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Column {
                        Text(
                            text = "客户端传输通道",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (uiState.status == ClientConnectionStatus.CONNECTED)
                                "已连接至 ${uiState.connectedIp} (${uiState.remoteFsName})"
                            else "输入目标 PC 服务端 IP 及端口",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    ClientConnectionStatusBadge(status = uiState.status)
                }
            }
        }

        // 2. Target Server Parameters Card
        item {
            ElevatedCard(
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "目标服务器设置",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // IP Field
                    OutlinedTextField(
                        value = uiState.targetIp,
                        onValueChange = { viewModel.onIpChanged(it) },
                        label = { Text("目标 IP 地址 (例如 192.168.1.100)") },
                        singleLine = true,
                        isError = uiState.ipError != null,
                        supportingText = {
                            if (uiState.ipError != null) {
                                Text(uiState.ipError ?: "", color = MaterialTheme.colorScheme.error)
                            }
                        },
                        trailingIcon = {
                            if (uiState.targetIp.isNotEmpty()) {
                                IconButton(onClick = { viewModel.onIpChanged("") }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear")
                                }
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                        enabled = uiState.status != ClientConnectionStatus.CONNECTING && uiState.status != ClientConnectionStatus.CONNECTED,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Port Field
                    OutlinedTextField(
                        value = uiState.targetPort,
                        onValueChange = { viewModel.onPortChanged(it) },
                        label = { Text("目标端口 (1..65535)") },
                        singleLine = true,
                        isError = uiState.portError != null,
                        supportingText = {
                            if (uiState.portError != null) {
                                Text(uiState.portError ?: "", color = MaterialTheme.colorScheme.error)
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        enabled = uiState.status != ClientConnectionStatus.CONNECTING && uiState.status != ClientConnectionStatus.CONNECTED,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Port Presets Bar
                    Text(
                        text = "常用端口快捷选择:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        uiState.portPresets.forEach { preset ->
                            AssistChip(
                                onClick = { viewModel.onPresetPortSelected(preset) },
                                label = { Text("$preset") },
                                enabled = uiState.status != ClientConnectionStatus.CONNECTED && uiState.status != ClientConnectionStatus.CONNECTING
                            )
                        }
                    }
                }
            }
        }

        // 3. Primary Connection Button
        item {
            if (uiState.status == ClientConnectionStatus.CONNECTED) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.disconnect() },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        Icon(Icons.Default.LinkOff, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("断开连接", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Button(
                            onClick = onNavigateToFiles,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                        ) {
                            Text("打开文件浏览")
                        }
                        Button(
                            onClick = onNavigateToDashboard,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                        ) {
                            Text("查看传输看板")
                        }
                    }
                }
            } else {
                Button(
                    onClick = { viewModel.connect() },
                    enabled = !uiState.isConnecting,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    if (uiState.isConnecting) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("正在连接服务端...", fontSize = 16.sp)
                    } else {
                        Icon(Icons.Default.Link, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("连接到服务端", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // 5. Connection History Card
        if (uiState.connectionHistory.isNotEmpty()) {
            item {
                ElevatedCard(
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.History,
                                    contentDescription = "History",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "历史连接记录",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            TextButton(onClick = { viewModel.clearHistory() }) {
                                Text("清空")
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        uiState.connectionHistory.forEach { item ->
                            HistoryItemRow(
                                item = item,
                                onClick = { viewModel.onHistoryItemSelected(item) },
                                onDelete = { viewModel.onHistoryItemDeleted(item) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryItemRow(
    item: ConnectionHistoryItem,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    val formattedTime = dateFormat.format(Date(item.timestampMs))

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${item.ip}:${item.port}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = formattedTime,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "Delete record",
                tint = MaterialTheme.colorScheme.outline
            )
        }
    }
}
