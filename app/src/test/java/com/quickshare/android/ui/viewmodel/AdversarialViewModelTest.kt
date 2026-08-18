package com.quickshare.android.ui.viewmodel

import com.quickshare.android.data.ConnectionHistoryItem
import com.quickshare.android.model.NetworkInterfaceInfo
import com.quickshare.android.model.RemoteFile
import com.quickshare.android.model.TransferDirection
import com.quickshare.android.model.TransferStatus
import com.quickshare.android.model.TransferTask
import com.quickshare.android.network.AggregatedTrafficSnapshot
import com.quickshare.android.network.ChannelTrafficSnapshot
import com.quickshare.android.protocol.QuickShareProtocolConstants
import com.quickshare.android.testdoubles.FakeAppConfigRepository
import com.quickshare.android.testdoubles.FakeQuickShareClient
import com.quickshare.android.testdoubles.FakeQuickShareServer
import com.quickshare.android.testdoubles.FakeInterfaceEnumerator
import com.quickshare.android.testdoubles.FakeStorageManager
import com.quickshare.android.testdoubles.FakeTrafficManager
import com.quickshare.android.util.MainDispatcherRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
class AdversarialViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var fakeClient: FakeQuickShareClient
    private lateinit var fakeServer: FakeQuickShareServer
    private lateinit var fakeStorage: FakeStorageManager
    private lateinit var fakeTrafficManager: FakeTrafficManager
    private lateinit var fakeEnumerator: FakeInterfaceEnumerator
    private lateinit var fakeAppConfigRepo: FakeAppConfigRepository

    @Before
    fun setUp() {
        fakeClient = FakeQuickShareClient()
        fakeServer = FakeQuickShareServer()
        fakeStorage = FakeStorageManager()
        fakeTrafficManager = FakeTrafficManager()
        fakeEnumerator = FakeInterfaceEnumerator()
        fakeAppConfigRepo = FakeAppConfigRepository()
    }

    // =========================================================================
    // 1. IP Validation & Edge Case Adversarial Tests
    // =========================================================================

    @Test
    fun testIpValidation_MalformedAndBoundaryCases() = runTest {
        val vm = ConnectionViewModel(fakeClient, fakeEnumerator, fakeAppConfigRepo)

        // Blank, whitespace, newlines
        val blankCases = listOf("", "   ", "\t", "\n", "  \r\n  ")
        for (ip in blankCases) {
            vm.onIpChanged(ip)
            assertEquals("Case '$ip' should fail with empty prompt", "请输入目标 IP 地址", vm.uiState.value.ipError)
        }

        // Out of range octets (> 255)
        val outOfRangeCases = listOf(
            "256.0.0.1", "192.168.1.300", "999.999.999.999", "1.2.3.256", "300.1.1.1"
        )
        for (ip in outOfRangeCases) {
            vm.onIpChanged(ip)
            assertEquals("Case '$ip' should fail with invalid format", "无效的 IPv4 地址格式", vm.uiState.value.ipError)
        }

        // Negative numbers or malformed characters
        val malformedCases = listOf(
            "-1.0.0.1", "192.-168.1.1", "192.168.1.-1", "abc.def.ghi.jkl", "192.168.1.1.1",
            "192.168.1", "192.168..1", "... ", "192.168.1.1:", "192.168.1.1:18888",
            "http://192.168.1.1", "https://192.168.1.1:80", "192.168.1.1/24", "192.168.1.1 "
        )
        for (ip in malformedCases) {
            vm.onIpChanged(ip)
            if (ip.trim() == "192.168.1.1") {
                assertNull(vm.uiState.value.ipError)
            } else {
                assertEquals("Case '$ip' should be rejected", "无效的 IPv4 地址格式", vm.uiState.value.ipError)
            }
        }

        // Security payloads & non-ASCII
        val securityPayloads = listOf(
            "192.168.1.1' OR '1'='1", "<script>alert(1)</script>", "192.168.1.1\u0000",
            "📱💻🌐", "::1", "fe80::1", "2001:0db8:85a3:0000:0000:8a2e:0370:7334"
        )
        for (ip in securityPayloads) {
            vm.onIpChanged(ip)
            assertEquals("Security payload '$ip' should be rejected", "无效的 IPv4 地址格式", vm.uiState.value.ipError)
        }

        // Valid IPv4 boundary addresses
        val validCases = listOf(
            "0.0.0.0", "127.0.0.1", "255.255.255.255", "192.168.0.1", "10.0.0.254", "172.16.0.1", "localhost"
        )
        for (ip in validCases) {
            vm.onIpChanged(ip)
            assertNull("Valid IP '$ip' should have no error", vm.uiState.value.ipError)
        }
    }

    // =========================================================================
    // 2. Port Validation & Edge Case Adversarial Tests
    // =========================================================================

    @Test
    fun testPortValidation_EdgeCasesAndOverflows() = runTest {
        val connVm = ConnectionViewModel(fakeClient, fakeEnumerator, fakeAppConfigRepo)
        val serverVm = ServerModeViewModel(fakeServer, fakeEnumerator, fakeAppConfigRepo)

        val invalidPorts = listOf(
            "" to "请输入端口号",
            "   " to "请输入端口号",
            "abc" to "端口必须为数字",
            "18888a" to "端口必须为数字",
            "18.888" to "端口必须为数字",
            "18 888" to "端口必须为数字",
            "0x49D0" to "端口必须为数字",
            "0" to "端口范围需在 1~65535 之间",
            "-1" to "端口范围需在 1~65535 之间",
            "-65535" to "端口范围需在 1~65535 之间",
            "65536" to "端口范围需在 1~65535 之间",
            "100000" to "端口范围需在 1~65535 之间",
            "999999999999999999999" to "端口必须为数字" // Long/BigInt overflow handled safely by toIntOrNull()
        )

        for ((portStr, expectedErr) in invalidPorts) {
            connVm.onPortChanged(portStr)
            assertEquals("ConnVM port '$portStr' error mismatch", expectedErr, connVm.uiState.value.portError)

            serverVm.onPortChanged(portStr)
            val expectedServerErr = if (expectedErr == "请输入端口号") "请输入监听端口" else expectedErr
            assertEquals("ServerVM port '$portStr' error mismatch", expectedServerErr, serverVm.uiState.value.portError)
        }

        val validPorts = listOf("1", "80", "443", "5740", "8080", "18888", "29999", "65535", " 18888 ")
        for (portStr in validPorts) {
            connVm.onPortChanged(portStr)
            assertNull("ConnVM port '$portStr' should be valid", connVm.uiState.value.portError)
            assertEquals(portStr.trim(), connVm.uiState.value.targetPort)

            serverVm.onPortChanged(portStr)
            assertNull("ServerVM port '$portStr' should be valid", serverVm.uiState.value.portError)
            assertEquals(portStr.trim(), serverVm.uiState.value.listenPort)
        }
    }

    // =========================================================================
    // 3. Concurrency Stress & Rapid User Interaction Tests
    // =========================================================================

    @Test
    fun testRapidConnectDisconnectToggles() = runTest {
        val vm = ConnectionViewModel(fakeClient, fakeEnumerator, fakeAppConfigRepo)
        vm.onIpChanged("192.168.1.100")
        vm.onPortChanged("18888")

        // Rapid toggling 20 times in succession
        for (i in 0 until 20) {
            fakeClient.shouldConnectSucceed = (i % 2 == 0)
            vm.connect()
            advanceUntilIdle()

            if (i % 2 == 0) {
                assertEquals(ClientConnectionStatus.CONNECTED, vm.uiState.value.status)
                vm.disconnect()
                advanceUntilIdle()
                assertEquals(ClientConnectionStatus.DISCONNECTED, vm.uiState.value.status)
            } else {
                assertEquals(ClientConnectionStatus.ERROR, vm.uiState.value.status)
                vm.clearError()
                assertNull(vm.uiState.value.errorMessage)
            }
        }
    }

    @Test
    fun testConcurrentSelectionTogglesAndModifications() = runTest {
        val vm = FileBrowserViewModel(fakeStorage, fakeClient, fakeServer, fakeAppConfigRepo, mainDispatcherRule.testDispatcher)
        advanceUntilIdle()

        val files = (1..50).map { i ->
            RemoteFile("file_$i.txt", "/sdcard/Download/file_$i.txt", 1000L * i, 1024L * i, false)
        }

        // Simulate concurrent user selections
        val jobs = (0 until 10).map {
            async(Dispatchers.Default) {
                for (file in files) {
                    vm.toggleFileSelection(file)
                }
            }
        }
        jobs.awaitAll()
        advanceUntilIdle()

        // Selection mode matches selected files count
        val state = vm.uiState.value
        assertEquals(state.selectedFiles.isNotEmpty(), state.isSelectionMode)

        // Select all and clear
        vm.selectAll()
        advanceUntilIdle()
        assertEquals(state.localFiles.size, vm.uiState.value.selectedFiles.size)
        assertTrue(vm.uiState.value.isSelectionMode)

        vm.clearSelection()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.selectedFiles.isEmpty())
        assertFalse(vm.uiState.value.isSelectionMode)
    }

    @Test
    fun testServerRapidToggleStartStop() = runTest {
        val vm = ServerModeViewModel(fakeServer, fakeEnumerator, fakeAppConfigRepo)
        vm.onPortChanged("29999")

        for (i in 0 until 15) {
            fakeServer.shouldStartSucceed = true
            vm.toggleServer() // Start
            advanceUntilIdle()
            assertTrue("Should be running at step $i", vm.uiState.value.isRunning)
            assertEquals(ServerRunningStatus.RUNNING, vm.uiState.value.status)

            vm.toggleServer() // Stop
            advanceUntilIdle()
            assertFalse("Should be stopped at step $i", vm.uiState.value.isRunning)
            assertEquals(ServerRunningStatus.STOPPED, vm.uiState.value.status)
        }
    }

    // =========================================================================
    // 4. Breadcrumb Navigation & Separator Stress Tests
    // =========================================================================

    @Test
    fun testBreadcrumb_ComplexWindowsAndUnixPaths() {
        // Deeply nested Windows path
        val winPath = "D:\\Projects\\Android\\QuickShare\\app\\src\\main\\java\\com\\quickshare\\android\\ui\\viewmodel"
        val winCrumbs = FileBrowserViewModel.generateBreadcrumbs(winPath, QuickShareProtocolConstants.FILE_SYSTEM_WINDOWS)
        assertEquals(13, winCrumbs.size)
        assertEquals("D:", winCrumbs[0].title)
        assertEquals("D:", winCrumbs[0].fullPath)
        assertEquals("Projects", winCrumbs[1].title)
        assertEquals("D:\\Projects", winCrumbs[1].fullPath)
        assertEquals("viewmodel", winCrumbs[12].title)
        assertEquals(winPath, winCrumbs[12].fullPath)

        // Deeply nested Unix path
        val unixPath = "/storage/emulated/0/Android/data/com.quickshare.android/files/Downloads/Sub1/Sub2"
        val unixCrumbs = FileBrowserViewModel.generateBreadcrumbs(unixPath, QuickShareProtocolConstants.FILE_SYSTEM_UNIX)
        assertEquals(11, unixCrumbs.size)
        assertEquals("根目录", unixCrumbs[0].title)
        assertEquals("/", unixCrumbs[0].fullPath)
        assertEquals("storage", unixCrumbs[1].title)
        assertEquals("/storage", unixCrumbs[1].fullPath)
        assertEquals("Sub2", unixCrumbs[10].title)
        assertEquals(unixPath, unixCrumbs[10].fullPath)

        // Mixed slashes on Windows path
        val mixedWin = "C:/Users\\Public/Documents\\Test"
        val mixedWinCrumbs = FileBrowserViewModel.generateBreadcrumbs(mixedWin, QuickShareProtocolConstants.FILE_SYSTEM_WINDOWS)
        assertEquals(5, mixedWinCrumbs.size)
        assertEquals("C:", mixedWinCrumbs[0].title)
        assertEquals("Test", mixedWinCrumbs[4].title)
        assertEquals("C:\\Users\\Public\\Documents\\Test", mixedWinCrumbs[4].fullPath)

        // Mixed slashes on Unix path
        val mixedUnix = "/sdcard\\Download/Music\\Rock"
        val mixedUnixCrumbs = FileBrowserViewModel.generateBreadcrumbs(mixedUnix, QuickShareProtocolConstants.FILE_SYSTEM_UNIX)
        assertEquals(5, mixedUnixCrumbs.size)
        assertEquals("根目录", mixedUnixCrumbs[0].title)
        assertEquals("Rock", mixedUnixCrumbs[4].title)
        assertEquals("/sdcard/Download/Music/Rock", mixedUnixCrumbs[4].fullPath)

        // Root boundaries
        val rootWin = FileBrowserViewModel.generateBreadcrumbs("C:\\", QuickShareProtocolConstants.FILE_SYSTEM_WINDOWS)
        assertEquals(1, rootWin.size)
        assertEquals("C:", rootWin[0].title)
        assertEquals("C:", rootWin[0].fullPath)

        val rootUnix = FileBrowserViewModel.generateBreadcrumbs("/", QuickShareProtocolConstants.FILE_SYSTEM_UNIX)
        assertEquals(1, rootUnix.size)
        assertEquals("根目录", rootUnix[0].title)
        assertEquals("/", rootUnix[0].fullPath)

        // Consecutive redundant slashes
        val redundantUnix = FileBrowserViewModel.generateBreadcrumbs("/sdcard///Download////Photos//", QuickShareProtocolConstants.FILE_SYSTEM_UNIX)
        assertEquals(4, redundantUnix.size)
        assertEquals("Photos", redundantUnix[3].title)
        assertEquals("/sdcard/Download/Photos", redundantUnix[3].fullPath)

        // Empty or blank path
        val blankCrumbs = FileBrowserViewModel.generateBreadcrumbs("   ", QuickShareProtocolConstants.FILE_SYSTEM_UNIX)
        assertEquals(1, blankCrumbs.size)
        assertEquals("根目录", blankCrumbs[0].title)
    }

    // =========================================================================
    // 5. File Sorting & Search Filter Edge Cases
    // =========================================================================

    @Test
    fun testFileSorting_BoundaryValuesAndInvariants() = runTest {
        val vm = FileBrowserViewModel(fakeStorage, fakeClient, fakeServer, fakeAppConfigRepo, mainDispatcherRule.testDispatcher)
        advanceUntilIdle()

        val mixedFiles = listOf(
            RemoteFile("z_folder", "/test/z_folder", 0L, 0L, true),
            RemoteFile("a_folder", "/test/a_folder", 0L, 0L, true),
            RemoteFile("0_byte_file.txt", "/test/0_byte_file.txt", 1000L, 0L, false),
            RemoteFile("max_byte_file.iso", "/test/max_byte_file.iso", 2000L, Long.MAX_VALUE, false),
            RemoteFile("future_time_file.bin", "/test/future_time_file.bin", 253402300799000L, 1024L, false),
            RemoteFile("zero_time_file.dat", "/test/zero_time_file.dat", 0L, 2048L, false),
            RemoteFile("duplicate_name.txt", "/test/duplicate_name.txt", 3000L, 500L, false),
            RemoteFile("DUPLICATE_NAME.txt", "/test/DUPLICATE_NAME.txt", 4000L, 600L, false)
        )

        fakeStorage.fileMap.clear()
        fakeStorage.fileMap.putAll(mixedFiles.associateBy { it.path })
        vm.navigateToLocal("/test")
        advanceUntilIdle()

        // 1. Sort by NAME ASCENDING
        vm.onSortChanged(SortField.NAME, SortOrder.ASCENDING)
        advanceUntilIdle()
        var list = vm.uiState.value.localFiles
        assertEquals(8, list.size)
        // Invariant: Directories MUST be at the top
        assertTrue(list[0].isDirectory)
        assertTrue(list[1].isDirectory)
        assertEquals("a_folder", list[0].name)
        assertEquals("z_folder", list[1].name)
        // Non-directories follow
        assertFalse(list[2].isDirectory)

        // 2. Sort by NAME DESCENDING
        vm.onSortChanged(SortField.NAME, SortOrder.DESCENDING)
        advanceUntilIdle()
        list = vm.uiState.value.localFiles
        // Invariant: Directories STILL on top, but ordered descending
        assertTrue(list[0].isDirectory)
        assertTrue(list[1].isDirectory)
        assertEquals("z_folder", list[0].name)
        assertEquals("a_folder", list[1].name)
        assertFalse(list[2].isDirectory)

        // 3. Sort by SIZE ASCENDING
        vm.onSortChanged(SortField.SIZE, SortOrder.ASCENDING)
        advanceUntilIdle()
        list = vm.uiState.value.localFiles
        assertTrue(list[0].isDirectory)
        assertTrue(list[1].isDirectory)
        assertEquals("0_byte_file.txt", list[2].name)
        assertEquals(0L, list[2].size)
        assertEquals("max_byte_file.iso", list.last().name)
        assertEquals(Long.MAX_VALUE, list.last().size)

        // 4. Sort by SIZE DESCENDING
        vm.onSortChanged(SortField.SIZE, SortOrder.DESCENDING)
        advanceUntilIdle()
        list = vm.uiState.value.localFiles
        assertTrue(list[0].isDirectory)
        assertTrue(list[1].isDirectory)
        assertEquals("max_byte_file.iso", list[2].name)
        assertEquals("0_byte_file.txt", list.last().name)

        // 5. Sort by DATE ASCENDING
        vm.onSortChanged(SortField.DATE, SortOrder.ASCENDING)
        advanceUntilIdle()
        list = vm.uiState.value.localFiles
        assertTrue(list[0].isDirectory)
        assertTrue(list[1].isDirectory)
        assertEquals("zero_time_file.dat", list[2].name)
        assertEquals("future_time_file.bin", list.last().name)

        // 6. Sort by DATE DESCENDING
        vm.onSortChanged(SortField.DATE, SortOrder.DESCENDING)
        advanceUntilIdle()
        list = vm.uiState.value.localFiles
        assertTrue(list[0].isDirectory)
        assertTrue(list[1].isDirectory)
        assertEquals("future_time_file.bin", list[2].name)
        assertEquals("zero_time_file.dat", list.last().name)
    }

    @Test
    fun testSearchFiltering_RegexAndSpecialCharacters() = runTest {
        val vm = FileBrowserViewModel(fakeStorage, fakeClient, fakeServer, fakeAppConfigRepo, mainDispatcherRule.testDispatcher)
        advanceUntilIdle()

        val files = listOf(
            RemoteFile("report (1).pdf", "/sdcard/report (1).pdf", 1000L, 1024L, false),
            RemoteFile("[backup] data.zip", "/sdcard/[backup] data.zip", 2000L, 2048L, false),
            RemoteFile("test*file+name.txt", "/sdcard/test*file+name.txt", 3000L, 512L, false),
            RemoteFile("regex.*match.log", "/sdcard/regex.*match.log", 4000L, 128L, false),
            RemoteFile("中文测试文件.doc", "/sdcard/中文测试文件.doc", 5000L, 256L, false)
        )
        fakeStorage.fileMap.clear()
        fakeStorage.fileMap.putAll(files.associateBy { it.path })
        vm.navigateToLocal("/sdcard")
        advanceUntilIdle()

        // Search with special regex symbols should NOT crash
        val queryTestCases = listOf(
            "(1)" to 1,
            "[backup]" to 1,
            "*" to 2,
            "+" to 1,
            ".*" to 1,
            "中文" to 1,
            "nonexistent" to 0
        )

        for ((query, expectedCount) in queryTestCases) {
            vm.onSearchQueryChanged(query)
            advanceUntilIdle()
            assertEquals("Query '$query' match count mismatch", expectedCount, vm.uiState.value.localFiles.size)
        }
    }

    // =========================================================================
    // 6. History Management & Corrupt JSON Resilience Tests
    // =========================================================================

    @Test
    fun testConnectionHistory_CapacityAndMRUDeduplication() = runTest {
        val repo = FakeAppConfigRepository()

        // Insert 25 distinct IP/port items
        for (i in 1..25) {
            repo.addConnectionHistory("192.168.1.$i", 18888 + i)
        }

        val history = repo.connectionHistory.value
        // Verify capacity is maintained
        assertTrue("History size should not exceed capacity", history.size <= 25)
        assertEquals("192.168.1.25", history.first().ip)

        // Re-insert earlier IP to test MRU reordering to head
        repo.addConnectionHistory("192.168.1.5", 18888 + 5)
        val updated = repo.connectionHistory.value
        assertEquals("192.168.1.5", updated.first().ip)
        assertEquals(18888 + 5, updated.first().port)
        // Ensure no duplicates of 192.168.1.5:18893
        assertEquals(1, updated.count { it.ip == "192.168.1.5" && it.port == 18888 + 5 })

        // Clear history
        repo.clearConnectionHistory()
        assertTrue(repo.connectionHistory.value.isEmpty())
    }

    @Test
    fun testDashboardViewModel_ExtremeTrafficMetricsAndTaskTransitions() = runTest {
        val vm = TransferDashboardViewModel(fakeTrafficManager, fakeClient, fakeServer)
        advanceUntilIdle()

        // 1. Extreme zero traffic snapshot
        val zeroSnapshot = AggregatedTrafficSnapshot(
            totalUploadSpeedBps = 0L,
            formattedSpeed = "0 B/s",
            totalCumulativeBytes = 0L,
            formattedTransferred = "0 B",
            totalTaskSize = 0L,
            formattedTotalSize = "0 B",
            progressPercent = 0.0,
            etaSeconds = 0,
            formattedEta = "--"
        )
        fakeTrafficManager.updateSnapshot(zeroSnapshot)
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isTransferActive)
        assertEquals("0 B/s", vm.uiState.value.totalSpeedFormatted)
        assertEquals("--", vm.uiState.value.etaFormatted)

        // 2. 100% completion traffic snapshot
        val completedSnapshot = AggregatedTrafficSnapshot(
            totalUploadSpeedBps = 0L,
            formattedSpeed = "0 B/s",
            totalCumulativeBytes = 1000L,
            formattedTransferred = "1000 B",
            totalTaskSize = 1000L,
            formattedTotalSize = "1000 B",
            progressPercent = 100.0,
            etaSeconds = 0,
            formattedEta = "0秒"
        )
        fakeTrafficManager.updateSnapshot(completedSnapshot)
        advanceUntilIdle()

        // Progress is 100.0 so isTransferActive is false
        assertFalse(vm.uiState.value.isTransferActive)

        // 3. Cancel when no active task
        vm.cancelActiveTransfer()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isTransferActive)
    }

    @Test
    fun testMainViewModel_ActiveTransferBadgeInterleaving() = runTest {
        val mainVm = MainViewModel(fakeClient, fakeServer, fakeStorage, fakeAppConfigRepo)
        advanceUntilIdle()

        assertEquals(0, mainVm.uiState.value.activeTransferBadgeCount)

        // Client task RUNNING -> count = 1
        val cTask = TransferTask("c1", "client.bin", "/sdcard/client.bin", TransferDirection.SEND, 1000L, 0L, 0.0, "0 KB/s", 0L, TransferStatus.RUNNING)
        fakeClient._currentTask.value = cTask
        advanceUntilIdle()
        assertEquals(1, mainVm.uiState.value.activeTransferBadgeCount)

        // Server tasks RUNNING -> count = 3
        val sTask1 = TransferTask("s1", "server1.bin", "/sdcard/server1.bin", TransferDirection.RECEIVE, 2000L, 0L, 0.0, "0 KB/s", 0L, TransferStatus.RUNNING)
        val sTask2 = TransferTask("s2", "server2.bin", "/sdcard/server2.bin", TransferDirection.RECEIVE, 3000L, 0L, 0.0, "0 KB/s", 0L, TransferStatus.RUNNING)
        val sTaskWaiting = TransferTask("s3", "server3.bin", "/sdcard/server3.bin", TransferDirection.RECEIVE, 4000L, 0L, 0.0, "0 KB/s", 0L, TransferStatus.WAITING)
        fakeServer._activeTransfers.value = listOf(sTask1, sTask2, sTaskWaiting)
        advanceUntilIdle()
        assertEquals(3, mainVm.uiState.value.activeTransferBadgeCount)

        // Client task finishes -> count = 2
        fakeClient._currentTask.value = cTask.withStatus(TransferStatus.COMPLETED)
        advanceUntilIdle()
        assertEquals(2, mainVm.uiState.value.activeTransferBadgeCount)

        // All server tasks finish -> count = 0
        fakeServer._activeTransfers.value = listOf(sTask1.withStatus(TransferStatus.COMPLETED), sTask2.withStatus(TransferStatus.FAILED))
        advanceUntilIdle()
        assertEquals(0, mainVm.uiState.value.activeTransferBadgeCount)
    }
}
