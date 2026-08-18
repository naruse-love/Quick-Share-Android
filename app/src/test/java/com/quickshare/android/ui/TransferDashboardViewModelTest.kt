package com.quickshare.android.ui

import com.quickshare.android.model.TransferDirection
import com.quickshare.android.model.TransferStatus
import com.quickshare.android.model.TransferTask
import com.quickshare.android.network.AggregatedTrafficSnapshot
import com.quickshare.android.network.ChannelTrafficSnapshot
import com.quickshare.android.testdoubles.FakeQuickShareClient
import com.quickshare.android.testdoubles.FakeQuickShareServer
import com.quickshare.android.testdoubles.FakeTrafficManager
import com.quickshare.android.ui.viewmodel.TransferDashboardViewModel
import com.quickshare.android.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TransferDashboardViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var fakeTrafficManager: FakeTrafficManager
    private lateinit var fakeClient: FakeQuickShareClient
    private lateinit var fakeServer: FakeQuickShareServer
    private lateinit var viewModel: TransferDashboardViewModel

    @Before
    fun setUp() {
        fakeTrafficManager = FakeTrafficManager()
        fakeClient = FakeQuickShareClient()
        fakeServer = FakeQuickShareServer()
        viewModel = TransferDashboardViewModel(fakeTrafficManager, fakeClient, fakeServer)
    }

    @Test
    fun testDefaultState() = runTest {
        val state = viewModel.uiState.value
        assertNull(state.activeTask)
        assertTrue(state.completedTasks.isEmpty())
        assertTrue(state.failedTasks.isEmpty())
        assertEquals("0 B/s", state.totalSpeedFormatted)
        assertEquals("--", state.etaFormatted)
        assertFalse(state.isTransferActive)
    }

    @Test
    fun testTrafficSnapshotUpdates() = runTest {
        val snapshot = AggregatedTrafficSnapshot(
            totalUploadSpeedBps = 100L * 1024 * 1024,
            formattedSpeed = "100.0 MB/s",
            totalCumulativeBytes = 500L * 1024 * 1024,
            formattedTransferred = "500.00 MB",
            totalTaskSize = 1000L * 1024 * 1024,
            formattedTotalSize = "1.00 GB",
            progressPercent = 50.0,
            etaSeconds = 5,
            formattedEta = "5秒",
            channelSnapshots = listOf(
                ChannelTrafficSnapshot("wlan0", 50L * 1024 * 1024, 0L, 250L * 1024 * 1024, 0L, "50.0 MB/s"),
                ChannelTrafficSnapshot("rndis0", 50L * 1024 * 1024, 0L, 250L * 1024 * 1024, 0L, "50.0 MB/s")
            )
        )

        fakeTrafficManager.updateSnapshot(snapshot)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("100.0 MB/s", state.totalSpeedFormatted)
        assertEquals("500.00 MB", state.totalTransferredFormatted)
        assertEquals("1.00 GB", state.totalSizeFormatted)
        assertEquals(50.0, state.progressPercent, 0.01)
        assertEquals("5秒", state.etaFormatted)
        assertEquals(2, state.channelSnapshots.size)
        assertTrue(state.isTransferActive)
    }

    @Test
    fun testActiveTaskTransitionsToCompleted() = runTest {
        val task = TransferTask(
            id = "task-1",
            fileName = "movie.mp4",
            direction = TransferDirection.SEND,
            size = 1000L,
            status = TransferStatus.RUNNING
        )

        fakeClient._currentTask.value = task
        advanceUntilIdle()

        assertEquals("movie.mp4", viewModel.uiState.value.activeTask?.fileName)
        assertTrue(viewModel.uiState.value.isTransferActive)

        // Complete task
        fakeClient._currentTask.value = task.withStatus(TransferStatus.COMPLETED)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.activeTask)
        assertFalse(viewModel.uiState.value.isTransferActive)
        assertEquals(1, viewModel.uiState.value.completedTasks.size)
        assertEquals("movie.mp4", viewModel.uiState.value.completedTasks.first().fileName)
    }

    @Test
    fun testActiveTaskTransitionsToFailed() = runTest {
        val task = TransferTask(
            id = "task-2",
            fileName = "archive.zip",
            direction = TransferDirection.RECEIVE,
            size = 5000L,
            status = TransferStatus.RUNNING
        )

        fakeClient._currentTask.value = task
        advanceUntilIdle()

        // Fail task
        fakeClient._currentTask.value = task.withStatus(TransferStatus.FAILED, "Disk full")
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.activeTask)
        assertEquals(1, viewModel.uiState.value.failedTasks.size)
        assertEquals("archive.zip", viewModel.uiState.value.failedTasks.first().fileName)
    }

    @Test
    fun testCancelActiveTransfer() = runTest {
        val task = TransferTask(
            id = "task-3",
            fileName = "large_iso.iso",
            direction = TransferDirection.SEND,
            size = 100000L,
            status = TransferStatus.RUNNING
        )

        fakeClient._currentTask.value = task
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isTransferActive)

        viewModel.cancelActiveTransfer()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.activeTask)
        assertFalse(viewModel.uiState.value.isTransferActive)
        assertEquals(1, viewModel.uiState.value.failedTasks.size)
        assertEquals(TransferStatus.CANCELLED, viewModel.uiState.value.failedTasks.first().status)
    }

    @Test
    fun testClearTaskHistory() = runTest {
        val completedTask = TransferTask(id = "1", fileName = "f1.txt", status = TransferStatus.COMPLETED)
        val failedTask = TransferTask(id = "2", fileName = "f2.txt", status = TransferStatus.FAILED)

        fakeClient._currentTask.value = completedTask
        advanceUntilIdle()
        fakeClient._currentTask.value = failedTask
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.completedTasks.size)
        assertEquals(1, viewModel.uiState.value.failedTasks.size)

        viewModel.clearTaskHistory()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.completedTasks.isEmpty())
        assertTrue(viewModel.uiState.value.failedTasks.isEmpty())
    }
}
