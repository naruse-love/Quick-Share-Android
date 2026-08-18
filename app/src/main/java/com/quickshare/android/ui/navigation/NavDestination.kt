package com.quickshare.android.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Speed
import androidx.compose.ui.graphics.vector.ImageVector
import com.quickshare.android.ui.viewmodel.AppTab

enum class NavDestination(
    val route: String,
    val title: String,
    val icon: ImageVector,
    val tab: AppTab
) {
    CONNECTION("connection", "客户端连接", Icons.Default.Cable, AppTab.CONNECTION),
    SERVER("server_mode", "服务端监听", Icons.Default.Dns, AppTab.SERVER_MODE),
    FILE_BROWSER("file_browser", "文件浏览", Icons.Default.Folder, AppTab.FILE_BROWSER),
    DASHBOARD("dashboard", "传输看板", Icons.Default.Speed, AppTab.DASHBOARD);

    companion object {
        fun fromRoute(route: String?): NavDestination {
            return entries.find { it.route == route } ?: CONNECTION
        }
    }
}
