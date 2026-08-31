package com.quickshare.android.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.quickshare.android.di.AppContainer

class QuickShareViewModelFactory(
    private val appContainer: AppContainer
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(MainViewModel::class.java) -> {
                MainViewModel(
                    quickShareClient = appContainer.quickShareClient,
                    quickShareServer = appContainer.quickShareServer,
                    storageManager = appContainer.storageManager,
                    appConfigRepo = appContainer.appConfigRepository
                ) as T
            }
            modelClass.isAssignableFrom(ConnectionViewModel::class.java) -> {
                ConnectionViewModel(
                    quickShareClient = appContainer.quickShareClient,
                    interfaceEnumerator = appContainer.interfaceEnumerator,
                    appConfigRepo = appContainer.appConfigRepository
                ) as T
            }
            modelClass.isAssignableFrom(ServerModeViewModel::class.java) -> {
                ServerModeViewModel(
                    quickShareServer = appContainer.quickShareServer,
                    interfaceEnumerator = appContainer.interfaceEnumerator,
                    appConfigRepo = appContainer.appConfigRepository
                ) as T
            }
            modelClass.isAssignableFrom(FileBrowserViewModel::class.java) -> {
                FileBrowserViewModel(
                    storageManager = appContainer.storageManager,
                    quickShareClient = appContainer.quickShareClient,
                    quickShareServer = appContainer.quickShareServer,
                    appConfigRepo = appContainer.appConfigRepository
                ) as T
            }
            modelClass.isAssignableFrom(TransferDashboardViewModel::class.java) -> {
                TransferDashboardViewModel(
                    trafficManager = appContainer.trafficManager,
                    quickShareClient = appContainer.quickShareClient,
                    quickShareServer = appContainer.quickShareServer,
                    transferHistoryRepo = appContainer.transferHistoryRepository
                ) as T
            }
            else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
