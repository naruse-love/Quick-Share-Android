package com.quickshare.android.ui

import com.quickshare.android.model.TransferDirection
import com.quickshare.android.model.TransferStatus
import com.quickshare.android.model.TransferTask
import com.quickshare.android.testdoubles.FakeAppConfigRepository
import com.quickshare.android.testdoubles.FakeQuickShareClient
import com.quickshare.android.testdoubles.FakeQuickShareServer
import com.quickshare.android.testdoubles.FakeStorageManager
import com.quickshare.android.ui.viewmodel.AppTab
import com.quickshare.android.ui.viewmodel.MainViewModel
import com.quickshare.android.ui.viewmodel.UiEvent
import com.quickshare.android.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var fakeClient: FakeQuickShareClient
    private lateinit var fakeServer: FakeQuickShareServer
    private lateinit var fakeStorage: FakeStorageManager
    private lateinit var fakeAppConfigRepo: FakeAppConfigRepository
    private lateinit var viewModel: MainViewModel

    @Before
    fun setUp() {
        fakeClient = FakeQuickShareClient()
        fakeServer = FakeQuickShareServer()
        fakeStorage = FakeStorageManager()
        fakeAppConfigRepo = FakeAppConfigRepository()
        viewModel = MainViewModel(fakeClient, fakeServer, fakeStorage, fakeAppConfigRepo)
    }

    @Test
    fun testDefaultState() = runTest {
        val state = viewModel.uiState.value
        assertEquals(AppTab.CONNECTION, state.currentTab)
        assertFalse(state.isClientConnected)
        assertFalse(state.isServerRunning)
        assertEquals(0, state.activeTransferBadgeCount)
        assertTrue(state.isStoragePermissionGranted)
    }

    @Test
    fun testTabSelection() = runTest {
        viewModel.selectTab(AppTab.SERVER_MODE)
        assertEquals(AppTab.SERVER_MODE, viewModel.uiState.value.currentTab)

        viewModel.selectTab(AppTab.FILE_BROWSER)
        assertEquals(AppTab.FILE_BROWSER, viewModel.uiState.value.currentTab)

        viewModel.selectTab(AppTab.DASHBOARD)
        assertEquals(AppTab.DASHBOARD, viewModel.uiState.value.currentTab)
    }

    @Test
    fun testStoragePermissionHandling() = runTest {
        fakeStorage.directAccess = false
        viewModel.checkStoragePermission()
        assertFalse(viewModel.uiState.value.isStoragePermissionGranted)

        viewModel.onStoragePermissionResult(true)
        assertTrue(viewModel.uiState.value.isStoragePermissionGranted)
    }

    @Test
    fun testEventEmission() = runTest {
        var receivedEvent: UiEvent? = null
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiEvents.collect {
                receivedEvent = it
            }
        }

        viewModel.emitEvent(UiEvent.ShowToast("Hello"))

        assertNotNull(receivedEvent)
        assertTrue(receivedEvent is UiEvent.ShowToast)
        assertEquals("Hello", (receivedEvent as UiEvent.ShowToast).message)
    }

    @Test
    fun testBadgeCountCoordination() = runTest {
        advanceUntilIdle()
        assertEquals(0, viewModel.uiState.value.activeTransferBadgeCount)

        // Client running task
        val clientTask = TransferTask(id = "c1", fileName = "f1.bin", status = TransferStatus.RUNNING)
        fakeClient._currentTask.value = clientTask
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.activeTransferBadgeCount)

        // Server running task
        val serverTask = TransferTask(id = "s1", fileName = "f2.bin", status = TransferStatus.RUNNING)
        fakeServer._activeTransfers.value = listOf(serverTask)
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.activeTransferBadgeCount)

        // Completed client task
        fakeClient._currentTask.value = clientTask.withStatus(TransferStatus.COMPLETED)
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.activeTransferBadgeCount)
    }

    @Test
    fun testConfigUpdates() = runTest {
        viewModel.updateSaveDirectory("/sdcard/QuickShareCustom")
        advanceUntilIdle()
        assertEquals("/sdcard/QuickShareCustom", fakeAppConfigRepo.appConfig.value.saveDirectory)

        viewModel.updateBufferCount(256)
        advanceUntilIdle()
        assertEquals(256, fakeAppConfigRepo.appConfig.value.bufferCount)

        viewModel.updateKeepScreenOn(false)
        advanceUntilIdle()
        assertFalse(fakeAppConfigRepo.appConfig.value.keepScreenOn)
    }
}
