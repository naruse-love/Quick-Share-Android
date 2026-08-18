package com.quickshare.android.ui

import com.quickshare.android.network.ClientSessionInfo
import com.quickshare.android.testdoubles.FakeAppConfigRepository
import com.quickshare.android.testdoubles.FakeQuickShareServer
import com.quickshare.android.testdoubles.FakeInterfaceEnumerator
import com.quickshare.android.ui.viewmodel.ServerModeViewModel
import com.quickshare.android.ui.viewmodel.ServerRunningStatus
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
class ServerModeViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var fakeServer: FakeQuickShareServer
    private lateinit var fakeEnumerator: FakeInterfaceEnumerator
    private lateinit var fakeAppConfigRepo: FakeAppConfigRepository
    private lateinit var viewModel: ServerModeViewModel

    @Before
    fun setUp() {
        fakeServer = FakeQuickShareServer()
        fakeEnumerator = FakeInterfaceEnumerator()
        fakeAppConfigRepo = FakeAppConfigRepository()
        viewModel = ServerModeViewModel(fakeServer, fakeEnumerator, fakeAppConfigRepo)
    }

    @Test
    fun testDefaultState() = runTest {
        val state = viewModel.uiState.value
        assertEquals("5740", state.listenPort)
        assertEquals(ServerRunningStatus.STOPPED, state.status)
        assertFalse(state.isRunning)
        assertEquals("服务未启动", state.statusText)
        assertEquals(3, state.activeNics.size)
        assertTrue(state.connectedClients.isEmpty())
        assertNull(state.errorMessage)
    }

    @Test
    fun testPortValidation() = runTest {
        viewModel.onPortChanged("")
        assertEquals("请输入监听端口", viewModel.uiState.value.portError)

        viewModel.onPortChanged("invalid")
        assertEquals("端口必须为数字", viewModel.uiState.value.portError)

        viewModel.onPortChanged("0")
        assertEquals("端口范围需在 1~65535 之间", viewModel.uiState.value.portError)

        viewModel.onPortChanged("70000")
        assertEquals("端口范围需在 1~65535 之间", viewModel.uiState.value.portError)

        viewModel.onPortChanged("29999")
        assertNull(viewModel.uiState.value.portError)
        assertEquals("29999", viewModel.uiState.value.listenPort)
    }

    @Test
    fun testPresetPortSelection() = runTest {
        viewModel.onPresetPortSelected(29999)
        assertEquals("29999", viewModel.uiState.value.listenPort)
        assertNull(viewModel.uiState.value.portError)
    }

    @Test
    fun testNicToggle() = runTest {
        viewModel.onNicToggled("wlan0", false)
        val wlan0 = viewModel.uiState.value.activeNics.find { it.name == "wlan0" }
        assertNotNull(wlan0)
        assertFalse(wlan0!!.isSelected)

        viewModel.onNicToggled("wlan0", true)
        val wlan0After = viewModel.uiState.value.activeNics.find { it.name == "wlan0" }
        assertTrue(wlan0After!!.isSelected)
    }

    @Test
    fun testStartServerSuccess() = runTest {
        fakeServer.shouldStartSucceed = true
        viewModel.onPortChanged("18888")
        viewModel.startServer()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(ServerRunningStatus.RUNNING, state.status)
        assertTrue(state.isRunning)
        assertTrue(state.statusText.contains("18888"))
        assertNull(state.errorMessage)
        assertEquals(18888, fakeAppConfigRepo.appConfig.value.port)
    }

    @Test
    fun testStartServerFailure() = runTest {
        fakeServer.shouldStartSucceed = false
        viewModel.onPortChanged("18888")
        viewModel.startServer()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(ServerRunningStatus.ERROR, state.status)
        assertFalse(state.isRunning)
        assertNotNull(state.errorMessage)
        assertTrue(state.errorMessage!!.contains("18888"))

        viewModel.clearError()
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun testStopServer() = runTest {
        fakeServer.shouldStartSucceed = true
        viewModel.startServer()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isRunning)

        viewModel.stopServer()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(ServerRunningStatus.STOPPED, state.status)
        assertFalse(state.isRunning)
    }

    @Test
    fun testToggleServer() = runTest {
        fakeServer.shouldStartSucceed = true
        viewModel.toggleServer() // Start
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isRunning)

        viewModel.toggleServer() // Stop
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isRunning)
    }

    @Test
    fun testConnectedClientsObservation() = runTest {
        val client = ClientSessionInfo(
            ipAddress = "192.168.1.50",
            port = 54321,
            remoteFileSystem = 0,
            remoteHomeDir = "D:\\",
            activeChannels = listOf("wlan0", "rndis0")
        )
        fakeServer._connectedClients.value = listOf(client)
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.connectedClients.size)
        assertEquals("192.168.1.50", viewModel.uiState.value.connectedClients.first().ipAddress)
    }
}
