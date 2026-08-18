package com.quickshare.android.ui

import com.quickshare.android.model.RemoteFile
import com.quickshare.android.protocol.QuickShareProtocolConstants
import com.quickshare.android.testdoubles.FakeAppConfigRepository
import com.quickshare.android.testdoubles.FakeQuickShareClient
import com.quickshare.android.testdoubles.FakeQuickShareServer
import com.quickshare.android.testdoubles.FakeStorageManager
import com.quickshare.android.ui.viewmodel.BrowserTab
import com.quickshare.android.ui.viewmodel.FileBrowserViewModel
import com.quickshare.android.ui.viewmodel.SortField
import com.quickshare.android.ui.viewmodel.SortOrder
import com.quickshare.android.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class FileBrowserViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var fakeStorage: FakeStorageManager
    private lateinit var fakeClient: FakeQuickShareClient
    private lateinit var fakeServer: FakeQuickShareServer
    private lateinit var fakeAppConfigRepo: FakeAppConfigRepository
    private lateinit var viewModel: FileBrowserViewModel

    @Before
    fun setUp() {
        fakeStorage = FakeStorageManager()
        fakeClient = FakeQuickShareClient()
        fakeServer = FakeQuickShareServer()
        fakeAppConfigRepo = FakeAppConfigRepository()
        viewModel = FileBrowserViewModel(fakeStorage, fakeClient, fakeServer, fakeAppConfigRepo, mainDispatcherRule.testDispatcher)
    }

    @Test
    fun testDefaultState() = runTest {
        advanceUntilIdle()
        val state = viewModel.uiState.value
        assertEquals(BrowserTab.LOCAL, state.activeTab)
        assertEquals("/sdcard/Download", state.currentLocalPath)
        assertFalse(state.isLocalLoading)
        assertTrue(state.localFiles.isNotEmpty())
        assertEquals(3, state.localBreadcrumbs.size)
    }

    @Test
    fun testBreadcrumbsGenerationUnixAndWindows() {
        // Unix path
        val unixCrumbs = FileBrowserViewModel.generateBreadcrumbs("/sdcard/Download/Music", QuickShareProtocolConstants.FILE_SYSTEM_UNIX)
        assertEquals(4, unixCrumbs.size)
        assertEquals("根目录", unixCrumbs[0].title)
        assertEquals("/", unixCrumbs[0].fullPath)
        assertEquals("sdcard", unixCrumbs[1].title)
        assertEquals("/sdcard", unixCrumbs[1].fullPath)
        assertEquals("Download", unixCrumbs[2].title)
        assertEquals("/sdcard/Download", unixCrumbs[2].fullPath)
        assertEquals("Music", unixCrumbs[3].title)
        assertEquals("/sdcard/Download/Music", unixCrumbs[3].fullPath)

        // Windows path
        val winCrumbs = FileBrowserViewModel.generateBreadcrumbs("C:\\Users\\Public\\Docs", QuickShareProtocolConstants.FILE_SYSTEM_WINDOWS)
        assertEquals(4, winCrumbs.size)
        assertEquals("C:", winCrumbs[0].title)
        assertEquals("C:", winCrumbs[0].fullPath)
        assertEquals("Users", winCrumbs[1].title)
        assertEquals("C:\\Users", winCrumbs[1].fullPath)
        assertEquals("Public", winCrumbs[2].title)
        assertEquals("C:\\Users\\Public", winCrumbs[2].fullPath)
        assertEquals("Docs", winCrumbs[3].title)
        assertEquals("C:\\Users\\Public\\Docs", winCrumbs[3].fullPath)
    }

    @Test
    fun testTabSwitching() = runTest {
        advanceUntilIdle()
        assertEquals(BrowserTab.LOCAL, viewModel.uiState.value.activeTab)

        fakeClient._isConnected.value = true
        fakeClient._remoteHomeDir.value = "C:\\Users\\Public"
        advanceUntilIdle()

        viewModel.switchTab(BrowserTab.REMOTE)
        advanceUntilIdle()

        assertEquals(BrowserTab.REMOTE, viewModel.uiState.value.activeTab)
        assertTrue(viewModel.uiState.value.isRemoteConnected)
        assertEquals("C:\\Users\\Public", viewModel.uiState.value.currentRemotePath)
        assertEquals(3, viewModel.uiState.value.remoteFiles.size)
    }

    @Test
    fun testSearchFiltering() = runTest {
        advanceUntilIdle()
        assertEquals(3, viewModel.uiState.value.localFiles.size)

        viewModel.onSearchQueryChanged("pdf")
        advanceUntilIdle()

        val filtered = viewModel.uiState.value.localFiles
        assertEquals(1, filtered.size)
        assertEquals("document.pdf", filtered.first().name)

        viewModel.onSearchQueryChanged("")
        advanceUntilIdle()
        assertEquals(3, viewModel.uiState.value.localFiles.size)
    }

    @Test
    fun testSortByNameSizeDate() = runTest {
        advanceUntilIdle()

        // Sort by Name Ascending: Folders first
        viewModel.onSortChanged(SortField.NAME, SortOrder.ASCENDING)
        advanceUntilIdle()
        val files = viewModel.uiState.value.localFiles
        assertTrue(files[0].isDirectory) // "photos"
        assertEquals("photos", files[0].name)
        assertEquals("document.pdf", files[1].name)
        assertEquals("song.mp3", files[2].name)

        // Sort by Size Ascending
        viewModel.onSortChanged(SortField.SIZE, SortOrder.ASCENDING)
        advanceUntilIdle()
        val sortedBySize = viewModel.uiState.value.localFiles
        assertEquals("photos", sortedBySize[0].name) // size 0
        assertEquals("document.pdf", sortedBySize[1].name) // size 5MB
        assertEquals("song.mp3", sortedBySize[2].name) // size 8MB
    }

    @Test
    fun testMultiSelection() = runTest {
        advanceUntilIdle()
        val file1 = viewModel.uiState.value.localFiles.first()
        val file2 = viewModel.uiState.value.localFiles.last()

        assertFalse(viewModel.uiState.value.isSelectionMode)
        assertTrue(viewModel.uiState.value.selectedFiles.isEmpty())

        viewModel.toggleFileSelection(file1)
        assertTrue(viewModel.uiState.value.isSelectionMode)
        assertEquals(1, viewModel.uiState.value.selectedFiles.size)

        viewModel.toggleFileSelection(file2)
        assertEquals(2, viewModel.uiState.value.selectedFiles.size)

        viewModel.selectAll()
        assertEquals(3, viewModel.uiState.value.selectedFiles.size)

        viewModel.clearSelection()
        assertFalse(viewModel.uiState.value.isSelectionMode)
        assertTrue(viewModel.uiState.value.selectedFiles.isEmpty())
    }

    @Test
    fun testLocalNavigation() = runTest {
        advanceUntilIdle()
        viewModel.navigateToLocal("/sdcard/Download/photos")
        advanceUntilIdle()

        assertEquals("/sdcard/Download/photos", viewModel.uiState.value.currentLocalPath)

        viewModel.navigateUpLocal()
        advanceUntilIdle()

        assertEquals("/sdcard/Download", viewModel.uiState.value.currentLocalPath)
    }

    @Test
    fun testCreateDirectoryAndDelete() = runTest {
        advanceUntilIdle()

        viewModel.createDirectory("NewFolder")
        advanceUntilIdle()

        assertTrue(fakeStorage.exists("/sdcard/Download/NewFolder"))

        val target = RemoteFile("NewFolder", "/sdcard/Download/NewFolder", 0L, 0L, true)
        viewModel.toggleFileSelection(target)
        viewModel.deleteSelectedFiles()
        advanceUntilIdle()

        assertFalse(fakeStorage.exists("/sdcard/Download/NewFolder"))
        assertTrue(viewModel.uiState.value.selectedFiles.isEmpty())
    }

    @Test
    fun testTransferSelectedFiles() = runTest {
        advanceUntilIdle()
        val file = viewModel.uiState.value.localFiles.first()
        viewModel.toggleFileSelection(file)

        var started = false
        viewModel.transferSelectedFiles { started = true }
        advanceUntilIdle()

        assertTrue(started)
        assertEquals("发送完成", viewModel.uiState.value.transferMessage)
        assertFalse(viewModel.uiState.value.isTransferring)
    }
}
