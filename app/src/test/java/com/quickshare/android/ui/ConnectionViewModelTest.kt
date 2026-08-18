package com.quickshare.android.ui

import com.quickshare.android.data.ConnectionHistoryItem
import com.quickshare.android.testdoubles.FakeAppConfigRepository
import com.quickshare.android.testdoubles.FakeQuickShareClient
import com.quickshare.android.testdoubles.FakeInterfaceEnumerator
import com.quickshare.android.ui.viewmodel.ClientConnectionStatus
import com.quickshare.android.ui.viewmodel.ConnectionViewModel
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
class ConnectionViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var fakeClient: FakeQuickShareClient
    private lateinit var fakeEnumerator: FakeInterfaceEnumerator
    private lateinit var fakeAppConfigRepo: FakeAppConfigRepository
    private lateinit var viewModel: ConnectionViewModel

    @Before
    fun setUp() {
        fakeClient = FakeQuickShareClient()
        fakeEnumerator = FakeInterfaceEnumerator()
        fakeAppConfigRepo = FakeAppConfigRepository()
        viewModel = ConnectionViewModel(fakeClient, fakeEnumerator, fakeAppConfigRepo)
    }

    @Test
    fun testDefaultState() = runTest {
        val state = viewModel.uiState.value
        assertEquals("18888", state.targetPort)
        assertEquals(ClientConnectionStatus.DISCONNECTED, state.status)
        assertFalse(state.isConnecting)
        assertNull(state.errorMessage)
        assertEquals(4, state.portPresets.size)
        assertEquals(3, state.availableNics.size)
    }

    @Test
    fun testIpValidation() = runTest {
        // Blank IP
        viewModel.onIpChanged("")
        assertNotNull(viewModel.uiState.value.ipError)

        // Invalid IP
        viewModel.onIpChanged("999.999.999.999")
        assertEquals("无效的 IPv4 地址格式", viewModel.uiState.value.ipError)

        // Invalid format letters
        viewModel.onIpChanged("abc.def.ghi")
        assertEquals("无效的 IPv4 地址格式", viewModel.uiState.value.ipError)

        // Valid IP
        viewModel.onIpChanged("192.168.1.150")
        assertNull(viewModel.uiState.value.ipError)

        // Valid localhost
        viewModel.onIpChanged("localhost")
        assertNull(viewModel.uiState.value.ipError)
    }

    @Test
    fun testPortValidation() = runTest {
        // Blank port
        viewModel.onPortChanged("")
        assertEquals("请输入端口号", viewModel.uiState.value.portError)

        // Non numeric
        viewModel.onPortChanged("abc")
        assertEquals("端口必须为数字", viewModel.uiState.value.portError)

        // Out of range (< 1)
        viewModel.onPortChanged("0")
        assertEquals("端口范围需在 1~65535 之间", viewModel.uiState.value.portError)

        // Out of range (> 65535)
        viewModel.onPortChanged("70000")
        assertEquals("端口范围需在 1~65535 之间", viewModel.uiState.value.portError)

        // Valid port
        viewModel.onPortChanged("29999")
        assertNull(viewModel.uiState.value.portError)
        assertEquals("29999", viewModel.uiState.value.targetPort)
    }

    @Test
    fun testPresetPortSelection() = runTest {
        viewModel.onPresetPortSelected(8080)
        assertEquals("8080", viewModel.uiState.value.targetPort)
        assertNull(viewModel.uiState.value.portError)
    }

    @Test
    fun testNicSelectionToggle() = runTest {
        val initialSelected = viewModel.uiState.value.availableNics.first().isSelected
        assertTrue(initialSelected)

        viewModel.onNicToggled("wlan0", false)
        val wlan0 = viewModel.uiState.value.availableNics.find { it.name == "wlan0" }
        assertNotNull(wlan0)
        assertFalse(wlan0!!.isSelected)

        viewModel.onNicToggled("wlan0", true)
        val wlan0After = viewModel.uiState.value.availableNics.find { it.name == "wlan0" }
        assertTrue(wlan0After!!.isSelected)
    }

    @Test
    fun testHistoryItemSelectionAndDeletion() = runTest {
        fakeAppConfigRepo.addConnectionHistory("10.0.0.12", 29999)
        advanceUntilIdle()

        val historyItem = fakeAppConfigRepo.connectionHistory.value.first()
        viewModel.onHistoryItemSelected(historyItem)

        assertEquals("10.0.0.12", viewModel.uiState.value.targetIp)
        assertEquals("29999", viewModel.uiState.value.targetPort)

        viewModel.onHistoryItemDeleted(historyItem)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.connectionHistory.isEmpty())
    }

    @Test
    fun testSuccessfulConnection() = runTest {
        viewModel.onIpChanged("192.168.1.100")
        viewModel.onPortChanged("18888")

        fakeClient.shouldConnectSucceed = true
        viewModel.connect()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(ClientConnectionStatus.CONNECTED, state.status)
        assertFalse(state.isConnecting)
        assertEquals("192.168.1.100", state.connectedIp)
        assertEquals("Windows", state.remoteFsName)
        assertEquals("C:\\Users\\Public", state.remoteHomeDir)
        assertNull(state.errorMessage)

        // Verify history was saved
        val history = fakeAppConfigRepo.connectionHistory.value
        assertTrue(history.any { it.ip == "192.168.1.100" && it.port == 18888 })
    }

    @Test
    fun testFailedConnection() = runTest {
        viewModel.onIpChanged("192.168.1.200")
        viewModel.onPortChanged("18888")

        fakeClient.shouldConnectSucceed = false
        fakeClient.failureReason = "Handshake timeout"

        viewModel.connect()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(ClientConnectionStatus.ERROR, state.status)
        assertFalse(state.isConnecting)
        assertEquals("Handshake timeout", state.errorMessage)

        viewModel.clearError()
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun testDisconnect() = runTest {
        viewModel.onIpChanged("192.168.1.100")
        viewModel.connect()
        advanceUntilIdle()
        assertEquals(ClientConnectionStatus.CONNECTED, viewModel.uiState.value.status)

        viewModel.disconnect()
        advanceUntilIdle()

        assertEquals(ClientConnectionStatus.DISCONNECTED, viewModel.uiState.value.status)
        assertEquals("", viewModel.uiState.value.connectedIp)
    }
}
