package com.tailcat.vpn.service

import android.content.Context
import android.content.Intent
import com.tailcat.vpn.core.NetworkMonitor
import com.tailcat.vpn.core.NetworkType
import com.tailcat.vpn.core.model.GatewayProfile
import com.tailcat.vpn.core.model.NetworkMetrics
import com.tailcat.vpn.core.model.TransportType
import com.tailcat.vpn.core.model.TunnelState
import com.tailcat.vpn.data.PreferencesStore
import com.tailcat.vpn.data.ProfileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class TunnelController(
    private val context: Context,
    private val profileRepository: ProfileRepository,
    private val preferencesStore: PreferencesStore,
    private val networkMonitor: NetworkMonitor
) {

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    val ipAuditor = com.tailcat.vpn.core.ip.IpAuditor()
    val egressInfo = ipAuditor.egressInfo

    private val _tunnelState = MutableStateFlow(TunnelState.DISCONNECTED)
    val tunnelState: StateFlow<TunnelState> = _tunnelState.asStateFlow()

    private val _networkMetrics = MutableStateFlow(NetworkMetrics())
    val networkMetrics: StateFlow<NetworkMetrics> = _networkMetrics.asStateFlow()

    private var pollingJob: Job? = null

    init {
        scope.launch {
            ipAuditor.fetchCurrentEgress()
        }

        networkMonitor.setOnNetworkChangedListener { networkType ->
            if (_tunnelState.value == TunnelState.CONNECTED && networkType != NetworkType.NONE) {
                onNetworkRoamed(networkType)
            }
        }
    }

    fun refreshEgressIp() {
        scope.launch {
            ipAuditor.fetchCurrentEgress()
        }
    }

    fun setTunnelState(state: TunnelState) {
        _tunnelState.value = state
    }

    fun startTunnel() {
        val profile = profileRepository.activeProfile.value ?: return
        val intent = Intent(context, TailcatVpnService::class.java).apply {
            action = TailcatVpnService.ACTION_START_VPN
        }
        context.startForegroundService(intent)
    }

    fun stopTunnel() {
        val intent = Intent(context, TailcatVpnService::class.java).apply {
            action = TailcatVpnService.ACTION_STOP_VPN
        }
        context.startService(intent)
    }

    fun onVpnInterfaceEstablished(tunFd: Int, profile: GatewayProfile) {
        _tunnelState.value = TunnelState.CONNECTED
        scope.launch {
            delay(500)
            ipAuditor.fetchCurrentEgress()
        }

        // Start metrics polling loop
        pollingJob?.cancel()
        pollingJob = scope.launch {
            var simulatedTx = 1024L
            var simulatedRx = 2048L

            while (isActive && _tunnelState.value == TunnelState.CONNECTED) {
                delay(1000)
                simulatedTx += (50..300).random() * 1024L
                simulatedRx += (100..800).random() * 1024L

                val isDirect = networkMonitor.activeNetworkType.value == NetworkType.WIFI &&
                        !preferencesStore.isAutoDerpOnEnterpriseWifi

                _networkMetrics.value = NetworkMetrics(
                    transportType = if (isDirect) TransportType.DIRECT_P2P else TransportType.DERP_RELAY,
                    derpRegionId = if (isDirect) null else (profile.derpRegionId ?: 1),
                    derpRegionName = if (isDirect) null else "NYC-1",
                    rttLatencyMs = if (isDirect) (18..35).random().toLong() else (70..140).random().toLong(),
                    jitterMs = (2..12).random().toLong(),
                    txBytes = simulatedTx,
                    rxBytes = simulatedRx,
                    txRateKbps = (40..180).random().toLong(),
                    rxRateKbps = (120..650).random().toLong()
                )
            }
        }
    }

    private fun onNetworkRoamed(networkType: NetworkType) {
        scope.launch {
            _tunnelState.value = TunnelState.RECONNECTING
            delay(1200)
            _tunnelState.value = TunnelState.CONNECTED
            ipAuditor.fetchCurrentEgress()
        }
    }

    fun onVpnStopped() {
        pollingJob?.cancel()
        _tunnelState.value = TunnelState.DISCONNECTED
        _networkMetrics.value = NetworkMetrics()
        scope.launch {
            delay(300)
            ipAuditor.fetchCurrentEgress()
        }
    }
}
