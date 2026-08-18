package com.quickshare.android.testdoubles

import com.quickshare.android.model.TransferDirection
import com.quickshare.android.network.AggregatedTrafficSnapshot
import com.quickshare.android.network.ITrafficManager
import com.quickshare.android.transfer.TransferConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeTrafficManager : ITrafficManager {

    private val _trafficState = MutableStateFlow(AggregatedTrafficSnapshot())
    override val trafficState: StateFlow<AggregatedTrafficSnapshot> = _trafficState.asStateFlow()

    fun updateSnapshot(snapshot: AggregatedTrafficSnapshot) {
        _trafficState.value = snapshot
    }

    override fun startMonitoring(
        connections: List<TransferConnection>,
        taskTotalSize: Long,
        direction: TransferDirection,
        transferredBytesProvider: () -> Long,
        coroutineScope: CoroutineScope
    ) {
    }

    override fun stopMonitoring() {
    }

    override fun reset() {
        _trafficState.value = AggregatedTrafficSnapshot()
    }
}
