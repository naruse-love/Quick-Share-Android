package com.quickshare.android.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.NetworkCell
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quickshare.android.model.InterfaceType
import com.quickshare.android.model.TransferStatus
import com.quickshare.android.ui.theme.QuickShareNicCellularColor
import com.quickshare.android.ui.theme.QuickShareNicEthernetColor
import com.quickshare.android.ui.theme.QuickShareNicUsbColor
import com.quickshare.android.ui.theme.QuickShareNicWifiColor
import com.quickshare.android.ui.theme.QuickShareSpeedGreen
import com.quickshare.android.ui.theme.QuickShareStatusConnected
import com.quickshare.android.ui.theme.QuickShareStatusConnecting
import com.quickshare.android.ui.theme.QuickShareStatusError
import com.quickshare.android.ui.theme.QuickShareStatusIdle
import com.quickshare.android.ui.viewmodel.ClientConnectionStatus
import com.quickshare.android.ui.viewmodel.ServerRunningStatus

@Composable
fun ClientConnectionStatusBadge(
    status: ClientConnectionStatus,
    modifier: Modifier = Modifier
) {
    val (dotColor, text) = when (status) {
        ClientConnectionStatus.DISCONNECTED -> Pair(QuickShareStatusIdle, "未连接")
        ClientConnectionStatus.CONNECTING -> Pair(QuickShareStatusConnecting, "连接中...")
        ClientConnectionStatus.CONNECTED -> Pair(QuickShareStatusConnected, "已连接")
        ClientConnectionStatus.ERROR -> Pair(QuickShareStatusError, "连接异常")
    }

    StatusPill(dotColor = dotColor, text = text, modifier = modifier)
}

@Composable
fun ServerStatusBadge(
    status: ServerRunningStatus,
    modifier: Modifier = Modifier
) {
    val (dotColor, text) = when (status) {
        ServerRunningStatus.STOPPED -> Pair(QuickShareStatusIdle, "未启动")
        ServerRunningStatus.STARTING -> Pair(QuickShareStatusConnecting, "正在启动...")
        ServerRunningStatus.RUNNING -> Pair(QuickShareStatusConnected, "运行中")
        ServerRunningStatus.ERROR -> Pair(QuickShareStatusError, "启动失败")
    }

    StatusPill(dotColor = dotColor, text = text, modifier = modifier)
}

@Composable
fun TransferStatusBadge(
    status: TransferStatus,
    modifier: Modifier = Modifier
) {
    val (color, text) = when (status) {
        TransferStatus.WAITING -> Pair(QuickShareStatusIdle, "等待中")
        TransferStatus.RUNNING -> Pair(QuickShareSpeedGreen, "传输中")
        TransferStatus.COMPLETED -> Pair(QuickShareStatusConnected, "已完成")
        TransferStatus.FAILED -> Pair(QuickShareStatusError, "失败")
        TransferStatus.CANCELLED -> Pair(QuickShareStatusIdle, "已取消")
    }

    Surface(
        color = color.copy(alpha = 0.15f),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = text,
                color = color,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun StatusPill(
    dotColor: Color,
    text: String,
    modifier: Modifier = Modifier
) {
    val animatedDotColor by animateColorAsState(targetValue = dotColor, label = "dotColor")

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(animatedDotColor)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

fun getNicIconAndColor(type: InterfaceType): Pair<ImageVector, Color> {
    return when (type) {
        InterfaceType.WIFI -> Pair(Icons.Default.Wifi, QuickShareNicWifiColor)
        InterfaceType.USB_TETHERING -> Pair(Icons.Default.Usb, QuickShareNicUsbColor)
        InterfaceType.ETHERNET -> Pair(Icons.Default.Cable, QuickShareNicEthernetColor)
        InterfaceType.CELLULAR -> Pair(Icons.Default.NetworkCell, QuickShareNicCellularColor)
        InterfaceType.OTHER -> Pair(Icons.Default.Cable, QuickShareStatusIdle)
    }
}
