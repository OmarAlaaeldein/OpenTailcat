package com.tailcat.vpn.ui.screens.speedtest

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tailcat.vpn.TailcatApplication
import com.tailcat.vpn.core.model.TunnelState
import com.tailcat.vpn.core.speedtest.SpeedTestEngine
import com.tailcat.vpn.core.speedtest.SpeedTestResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SpeedTestViewModel : ViewModel() {

    private val engine = SpeedTestEngine()
    val testState: StateFlow<SpeedTestResult> = engine.testState

    private var activeJob: Job? = null

    fun startSpeedTest() {
        activeJob?.cancel()
        val viaGateway = TailcatApplication.instance.tunnelController.tunnelState.value == TunnelState.CONNECTED
        activeJob = viewModelScope.launch {
            engine.runSpeedTest(viaGateway)
        }
    }

    fun reset() {
        activeJob?.cancel()
        engine.reset()
    }
}
