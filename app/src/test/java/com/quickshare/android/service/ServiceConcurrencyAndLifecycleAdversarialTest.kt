package com.quickshare.android.service

import com.quickshare.android.model.TransferDirection
import com.quickshare.android.model.TransferStatus
import com.quickshare.android.model.TransferTask
import com.quickshare.android.testdoubles.FakeQuickShareClient
import com.quickshare.android.testdoubles.FakeQuickShareServer
import com.quickshare.android.testdoubles.FakeTrafficManager
import com.quickshare.android.ui.viewmodel.TransferDashboardViewModel
import com.quickshare.android.util.MainDispatcherRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Adversarial Concurrency & Lifecycle test suite:
 * - Abrupt cancellation while coroutines are streaming
 * - Concurrent task updates & race condition safety in TransferDashboardViewModel
 * - Exception injection during coroutine streaming
 * - Deduplication invariants under multi-threaded contention
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServiceConcurrencyAndLifecycleAdversarialTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun testAbruptCancellationWhileActiveTransferStreaming() = runTest {
        val trafficManager = FakeTrafficManager()
        val client = FakeQuickShareClient()
        val server = FakeQuickShareServer()

        val viewModel = TransferDashboardViewModel(trafficManager, client, server)

        val task = TransferTask(
            id = "task-stream-001",
            fileName = "streaming_video.mp4",
            filePath = "/sdcard/streaming_video.mp4",
            direction = TransferDirection.SEND,
            size = 500 * 1024 * 1024L,
            status = TransferStatus.RUNNING
        )

        // Set active task
        client._currentTask.value = task
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isTransferActive)
        assertEquals("task-stream-001", viewModel.uiState.value.activeTask?.id)

        // Abruptly cancel transfer
        viewModel.cancelActiveTransfer()
        advanceUntilIdle()

        // Verify state after cancellation
        assertFalse(viewModel.uiState.value.isTransferActive)
        assertNull(viewModel.uiState.value.activeTask)
        assertFalse(client._isConnected.value)

        val failedList = viewModel.uiState.value.failedTasks
        assertEquals(1, failedList.size)
        assertEquals("task-stream-001", failedList[0].id)
        assertEquals(TransferStatus.CANCELLED, failedList[0].status)
        assertEquals("用户已取消传输", failedList[0].errorMessage)
    }

    @Test
    fun testMultipleConcurrentTaskUpdatesAndDeduplication() = runTest {
        val trafficManager = FakeTrafficManager()
        val client = FakeQuickShareClient()
        val server = FakeQuickShareServer()

        val viewModel = TransferDashboardViewModel(trafficManager, client, server)

        val taskCount = 30
        val threadCount = 4

        // Concurrently emit completed and failed tasks across multiple coroutines
        val jobs = (0 until threadCount).map {
            async(Dispatchers.Default) {
                for (i in 0 until taskCount) {
                    val taskId = "concurrent-task-$i"
                    val isComplete = (i % 2 == 0)
                    val task = TransferTask(
                        id = taskId,
                        fileName = "file_$i.bin",
                        filePath = "/sdcard/file_$i.bin",
                        direction = if (i % 3 == 0) TransferDirection.SEND else TransferDirection.RECEIVE,
                        size = (i + 1) * 1024 * 1024L,
                        status = if (isComplete) TransferStatus.COMPLETED else TransferStatus.FAILED,
                        errorMessage = if (!isComplete) "Network timeout on socket $i" else null
                    )
                    // Emit to client flow
                    client._currentTask.value = task
                    delay(2)
                }
            }
        }

        jobs.awaitAll()
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        // Ensure no duplicate IDs in completed list
        val completedIds = uiState.completedTasks.map { it.id }
        assertEquals(completedIds.toSet().size, completedIds.size)

        // Ensure no duplicate IDs in failed list
        val failedIds = uiState.failedTasks.map { it.id }
        assertEquals(failedIds.toSet().size, failedIds.size)

        // Clear history and verify
        viewModel.clearTaskHistory()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.completedTasks.isEmpty())
        assertTrue(viewModel.uiState.value.failedTasks.isEmpty())
    }

    @Test
    fun testExceptionDuringCoroutineStreaming() = runTest {
        val trafficManager = FakeTrafficManager()
        val client = FakeQuickShareClient()
        val server = FakeQuickShareServer()

        val viewModel = TransferDashboardViewModel(trafficManager, client, server)

        val runningTask = TransferTask(
            id = "task-err-01",
            fileName = "corrupt_stream.tar",
            filePath = "/sdcard/corrupt_stream.tar",
            direction = TransferDirection.RECEIVE,
            size = 100 * 1024 * 1024L,
            status = TransferStatus.RUNNING
        )
        client._currentTask.value = runningTask
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isTransferActive)

        // Simulate streaming exception resulting in FAILED task update
        val failedTask = runningTask.withStatus(
            TransferStatus.FAILED,
            "SocketException: Connection reset by peer"
        )
        client._currentTask.value = failedTask
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isTransferActive)
        assertNull(viewModel.uiState.value.activeTask)
        assertEquals(1, viewModel.uiState.value.failedTasks.size)
        assertEquals("SocketException: Connection reset by peer", viewModel.uiState.value.failedTasks[0].errorMessage)
    }

    @Test
    fun testConcurrentClientAndServerTaskInterleaving() = runTest {
        val trafficManager = FakeTrafficManager()
        val client = FakeQuickShareClient()
        val server = FakeQuickShareServer()

        val viewModel = TransferDashboardViewModel(trafficManager, client, server)

        // Client task
        val clientTask = TransferTask(
            id = "client-task-1",
            fileName = "client_upload.zip",
            filePath = "/sdcard/client_upload.zip",
            direction = TransferDirection.SEND,
            size = 20 * 1024 * 1024L,
            status = TransferStatus.RUNNING
        )
        client._currentTask.value = clientTask
        advanceUntilIdle()
        assertEquals("client-task-1", viewModel.uiState.value.activeTask?.id)

        // Server receives an active transfer task
        val serverTask = TransferTask(
            id = "server-task-1",
            fileName = "server_incoming.iso",
            filePath = "/sdcard/server_incoming.iso",
            direction = TransferDirection.RECEIVE,
            size = 800 * 1024 * 1024L,
            status = TransferStatus.RUNNING
        )
        server._activeTransfers.value = listOf(serverTask)
        advanceUntilIdle()

        // Both complete
        client._currentTask.value = clientTask.withStatus(TransferStatus.COMPLETED)
        server._activeTransfers.value = emptyList()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.activeTask)
        assertFalse(viewModel.uiState.value.isTransferActive)
        assertTrue(viewModel.uiState.value.completedTasks.any { it.id == "client-task-1" })
    }
}
