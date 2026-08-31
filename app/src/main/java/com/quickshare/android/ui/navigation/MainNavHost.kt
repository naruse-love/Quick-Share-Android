package com.quickshare.android.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.quickshare.android.ui.screens.ConnectionScreen
import com.quickshare.android.ui.screens.FileBrowserScreen
import com.quickshare.android.ui.screens.ServerModeScreen
import com.quickshare.android.ui.screens.TransferDashboardScreen
import com.quickshare.android.ui.viewmodel.AppTab
import com.quickshare.android.ui.viewmodel.ConnectionViewModel
import com.quickshare.android.ui.viewmodel.FileBrowserViewModel
import com.quickshare.android.ui.viewmodel.MainViewModel
import com.quickshare.android.ui.viewmodel.ServerModeViewModel
import com.quickshare.android.ui.viewmodel.TransferDashboardViewModel

@Composable
fun MainNavHost(
    navController: NavHostController,
    mainViewModel: MainViewModel,
    connectionViewModel: ConnectionViewModel,
    serverModeViewModel: ServerModeViewModel,
    fileBrowserViewModel: FileBrowserViewModel,
    transferDashboardViewModel: TransferDashboardViewModel,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = NavDestination.CONNECTION.route,
        modifier = modifier
    ) {
        composable(NavDestination.CONNECTION.route) {
            ConnectionScreen(
                viewModel = connectionViewModel,
                onNavigateToFiles = {
                    mainViewModel.selectTab(AppTab.FILE_BROWSER)
                    navController.navigate(NavDestination.FILE_BROWSER.route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                onNavigateToDashboard = {
                    mainViewModel.selectTab(AppTab.DASHBOARD)
                    navController.navigate(NavDestination.DASHBOARD.route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            )
        }

        composable(NavDestination.SERVER.route) {
            ServerModeScreen(
                viewModel = serverModeViewModel,
                onNavigateToDashboard = {
                    mainViewModel.selectTab(AppTab.DASHBOARD)
                    navController.navigate(NavDestination.DASHBOARD.route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            )
        }

        composable(NavDestination.FILE_BROWSER.route) {
            FileBrowserScreen(
                viewModel = fileBrowserViewModel,
                onNavigateToDashboard = {
                    mainViewModel.selectTab(AppTab.DASHBOARD)
                    navController.navigate(NavDestination.DASHBOARD.route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            )
        }

        composable(NavDestination.DASHBOARD.route) {
            TransferDashboardScreen(
                viewModel = transferDashboardViewModel,
                onNavigateToBrowser = {
                    mainViewModel.selectTab(AppTab.FILE_BROWSER)
                    navController.navigate(NavDestination.FILE_BROWSER.route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            )
        }
    }
}
