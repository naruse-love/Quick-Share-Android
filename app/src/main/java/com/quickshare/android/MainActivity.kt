package com.quickshare.android

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.quickshare.android.ui.components.QuickShareBottomBar
import com.quickshare.android.ui.components.QuickShareTopAppBar
import com.quickshare.android.ui.components.NotificationPermissionRationaleDialog
import com.quickshare.android.ui.components.StoragePermissionRationaleDialog
import com.quickshare.android.ui.navigation.MainNavHost
import com.quickshare.android.ui.navigation.NavDestination
import com.quickshare.android.ui.theme.QuickShareTheme
import com.quickshare.android.ui.viewmodel.AppTab
import com.quickshare.android.ui.viewmodel.ConnectionViewModel
import com.quickshare.android.ui.viewmodel.FileBrowserViewModel
import com.quickshare.android.ui.viewmodel.QuickShareViewModelFactory
import com.quickshare.android.ui.viewmodel.MainViewModel
import com.quickshare.android.ui.viewmodel.ServerModeViewModel
import com.quickshare.android.ui.viewmodel.TransferDashboardViewModel
import com.quickshare.android.ui.viewmodel.UiEvent
import com.quickshare.android.util.PermissionHelper
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val appContainer get() = (application as QuickShareApplication).appContainer
    private val factory by lazy { QuickShareViewModelFactory(appContainer) }

    private val mainViewModel: MainViewModel by viewModels { factory }
    private val connectionViewModel: ConnectionViewModel by viewModels { factory }
    private val serverModeViewModel: ServerModeViewModel by viewModels { factory }
    private val fileBrowserViewModel: FileBrowserViewModel by viewModels { factory }
    private val transferDashboardViewModel: TransferDashboardViewModel by viewModels { factory }

    private val manageAllFilesLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val granted = PermissionHelper.hasStoragePermission(this)
        mainViewModel.onStoragePermissionResult(granted)
    }

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(this, "未开启通知权限，传输进度将不在状态栏显示", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        handleIncomingIntent(intent)
        checkRuntimePermissions()

        setContent {
            QuickShareTheme {
                val navController = rememberNavController()
                val mainUiState by mainViewModel.uiState.collectAsStateWithLifecycle()
                val snackbarHostState = remember { SnackbarHostState() }
                val scope = rememberCoroutineScope()

                var showStorageRationale by remember { mutableStateOf(false) }
                var showNotificationRationale by remember { mutableStateOf(false) }

                LaunchedEffect(Unit) {
                    mainViewModel.uiEvents.collect { event ->
                        when (event) {
                            is UiEvent.ShowSnackbar -> {
                                scope.launch {
                                    snackbarHostState.showSnackbar(event.message, event.actionLabel)
                                }
                            }
                            is UiEvent.ShowToast -> {
                                Toast.makeText(this@MainActivity, event.message, Toast.LENGTH_SHORT).show()
                            }
                            is UiEvent.NavigateToTab -> {
                                val destination = NavDestination.entries.find { it.tab == event.tab }
                                if (destination != null) {
                                    navController.navigate(destination.route) {
                                        launchSingleTop = true
                                    }
                                }
                            }
                            is UiEvent.RequestStoragePermission -> {
                                if (!PermissionHelper.hasStoragePermission(this@MainActivity)) {
                                    showStorageRationale = true
                                }
                            }
                            is UiEvent.ShowErrorDialog -> {
                                Toast.makeText(this@MainActivity, "${event.title}: ${event.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }

                if (showStorageRationale) {
                    StoragePermissionRationaleDialog(
                        onDismiss = { showStorageRationale = false },
                        onGrantClick = {
                            showStorageRationale = false
                            val intent = PermissionHelper.createManageAllFilesIntent(this@MainActivity)
                            manageAllFilesLauncher.launch(intent)
                        }
                    )
                }

                if (showNotificationRationale) {
                    NotificationPermissionRationaleDialog(
                        onDismiss = { showNotificationRationale = false },
                        onGrantClick = {
                            showNotificationRationale = false
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                requestNotificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }
                    )
                }

                Scaffold(
                    topBar = {
                        QuickShareTopAppBar(uiState = mainUiState)
                    },
                    bottomBar = {
                        QuickShareBottomBar(
                            currentTab = mainUiState.currentTab,
                            badgeCount = mainUiState.activeTransferBadgeCount,
                            onTabSelected = { destination ->
                                mainViewModel.selectTab(destination.tab)
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.startDestinationId) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    },
                    snackbarHost = {
                        SnackbarHost(hostState = snackbarHostState)
                    },
                    modifier = Modifier.fillMaxSize()
                ) { innerPadding ->
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        MainNavHost(
                            navController = navController,
                            mainViewModel = mainViewModel,
                            connectionViewModel = connectionViewModel,
                            serverModeViewModel = serverModeViewModel,
                            fileBrowserViewModel = fileBrowserViewModel,
                            transferDashboardViewModel = transferDashboardViewModel
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        val granted = PermissionHelper.hasStoragePermission(this)
        mainViewModel.onStoragePermissionResult(granted)
    }

    private fun checkRuntimePermissions() {
        if (!PermissionHelper.hasNotificationPermission(this) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        if (!PermissionHelper.hasStoragePermission(this)) {
            mainViewModel.emitEvent(UiEvent.RequestStoragePermission)
        }
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return

        val targetTab = intent.getStringExtra("target_tab")
        if (targetTab == "dashboard") {
            mainViewModel.selectTab(AppTab.DASHBOARD)
            mainViewModel.emitEvent(UiEvent.NavigateToTab(AppTab.DASHBOARD))
            return
        }

        // Deep Link: quickshare://connect?ip=...&port=...
        val data: Uri? = intent.data
        if (data != null && data.scheme == "quickshare") {
            val ip = data.getQueryParameter("ip")
            val port = data.getQueryParameter("port")
            if (!ip.isNullOrBlank()) {
                connectionViewModel.onIpChanged(ip)
            }
            if (!port.isNullOrBlank()) {
                connectionViewModel.onPortChanged(port)
            }
            mainViewModel.selectTab(AppTab.CONNECTION)
            mainViewModel.emitEvent(UiEvent.NavigateToTab(AppTab.CONNECTION))
            return
        }

        // Action SEND / SEND_MULTIPLE from other apps
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                if (uri != null) {
                    Toast.makeText(this, "已接收外部分享文件: $uri", Toast.LENGTH_SHORT).show()
                    mainViewModel.selectTab(AppTab.FILE_BROWSER)
                    mainViewModel.emitEvent(UiEvent.NavigateToTab(AppTab.FILE_BROWSER))
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
                if (!uris.isNullOrEmpty()) {
                    Toast.makeText(this, "已接收 ${uris.size} 个外部分享文件", Toast.LENGTH_SHORT).show()
                    mainViewModel.selectTab(AppTab.FILE_BROWSER)
                    mainViewModel.emitEvent(UiEvent.NavigateToTab(AppTab.FILE_BROWSER))
                }
            }
        }
    }
}
