package com.quickshare.android.ui.components

import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.quickshare.android.ui.navigation.NavDestination
import com.quickshare.android.ui.viewmodel.AppTab

@Composable
fun QuickShareBottomBar(
    currentTab: AppTab,
    badgeCount: Int,
    onTabSelected: (NavDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    NavigationBar(modifier = modifier) {
        NavDestination.entries.forEach { destination ->
            val selected = currentTab == destination.tab
            NavigationBarItem(
                selected = selected,
                onClick = { onTabSelected(destination) },
                icon = {
                    if (destination == NavDestination.DASHBOARD && badgeCount > 0) {
                        BadgedBox(
                            badge = { Badge { Text("$badgeCount") } }
                        ) {
                            Icon(destination.icon, contentDescription = destination.title)
                        }
                    } else {
                        Icon(destination.icon, contentDescription = destination.title)
                    }
                },
                label = { Text(destination.title) }
            )
        }
    }
}
