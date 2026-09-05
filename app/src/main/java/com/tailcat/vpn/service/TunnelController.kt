package com.tailcat.vpn.service

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import com.tailcat.vpn.core.NetworkMonitor
import com.tailcat.vpn.core.NetworkType
import com.tailcat.vpn.core.model.NetworkMetrics
import com.tailcat.vpn.core.model.TunnelState
import com.tailcat.vpn.core.token.TokenParser
import com.tailcat.vpn.core.token.TokenValidationState
import com.tailcat.vpn.data.PreferencesStorage
import com.tailcat.vpn.data.ProfileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class TunnelController(
    private val context: Context,
    private val profileRepository: ProfileRepository,
    private val networkMonitor: NetworkMonitor,
    private val tunnelEngine: NativeEngine,
    private val preferences: PreferencesStorage
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val ipAuditor = com.tailcat.vpn.core.ip.IpAuditor()
    val egressInfo = ipAuditor.egressInfo

    private val _tunnelState = MutableStateFlow(TunnelState.DISCONNECTED)
    val tunnelState: StateFlow<TunnelState> = _tunnelState.asStateFlow()

    private val _tunnelEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val tunnelEvents: SharedFlow<String> = _tunnelEvents.asSharedFlow()

    private val _networkMetrics = MutableStateFlow(NetworkMetrics())
    val networkMetrics: StateFlow<NetworkMetrics> = _networkMetrics.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    val engineAvailability: EngineAvailability
        get() = tunnelEngine.availability

    private var pollingJob: Job? = null

    init {
        scope.launch { ipAuditor.fetchCurrentEgress() }

        networkMonitor.setOnNetworkStateChangedListener { networkType, stateJson ->
            tunnelEngine.updateNetworkState(stateJson)
            when {
                networkType == NetworkType.NONE && _tunnelState.value == TunnelState.CONNECTED -> {
                    _tunnelState.value = TunnelState.RECONNECTING
                }
                networkType != NetworkType.NONE && _tunnelState.value == TunnelState.RECONNECTING -> {
                    scope.launch {
                        runCatching { tunnelEngine.getStats() }
                            .onSuccess { metrics ->
                                _networkMetrics.value = metrics
                                if (EngineHealth.shouldConnect(metrics, unixNow())) {
                                    _tunnelState.value = TunnelState.CONNECTED
                                }
                            }
                    }
                }
            }
        }
    }

    fun refreshPublicIp() {
        scope.launch { ipAuditor.fetchCurrentEgress() }
    }

    fun setTunnelState(state: TunnelState) {
        _tunnelState.value = state
    }

    fun validateStartRequest(): String? {
        val profile = profileRepository.activeProfile.value
            ?: return "Pair a gateway token before connecting"
        if (!networkMonitor.isOnline) return "No validated internet connection is available"
        if (preferences.splitTunnelExcludedApps.isNotEmpty()) return LeakGuard.SPLIT_TUNNEL_BLOCKED

        return when (val validation = TokenParser.validate(profile.token)) {
            is TokenValidationState.Valid -> {
                if (tunnelEngine.availability.isAvailable) null else tunnelEngine.availability.message
            }
            is TokenValidationState.Expired -> "The selected gateway token has expired"
            is TokenValidationState.LegacyReissueRequired -> "Legacy token schema lacks separate disco key; reissue required"
            is TokenValidationState.Invalid -> validation.reason
            TokenValidationState.Empty -> "The selected gateway token is empty"
        }
    }

    fun resyncFromEngine() {
        if (_tunnelState.value == TunnelState.CONNECTED) return
        scope.launch {
            runCatching { tunnelEngine.getStats() }
                .onSuccess { metrics ->
                    if (EngineHealth.shouldConnect(metrics, unixNow()) &&
                        _tunnelState.value != TunnelState.CONNECTED
                    ) {
                        onEngineConnected(metrics)
                    } else {
                        restoreIfWanted()
                    }
                }
                .onFailure { restoreIfWanted() }
        }
    }

    fun restoreIfWanted(): Boolean {
        if (!VpnRestore.shouldRestore(
                vpnWanted = preferences.vpnWanted,
                state = _tunnelState.value,
                validationError = validateStartRequest()
            )
        ) {
            return false
        }
        return startTunnel()
    }

    fun startTunnel(): Boolean {
        val error = validateStartRequest()
        if (error != null) {
            reportError(error)
            return false
        }

        return runCatching {
            // Automatic restoration has no Activity to obtain consent. Do not
            // enqueue a foreground-service start until Android has granted it.
            check(VpnService.prepare(context) == null) { TailcatVpnService.VPN_PERMISSION_REQUIRED }
            preferences.vpnWanted = true
            _lastError.value = null
            _tunnelState.value = TunnelState.CONNECTING
            val intent = Intent(context, TailcatVpnService::class.java).apply {
                action = TailcatVpnService.ACTION_START_VPN
            }
            ContextCompat.startForegroundService(context, intent)
            true
        }.getOrElse {
            onVpnStartFailed(it.message ?: "Android could not start the VPN service")
            false
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
    }

    fun stopTunnel() {
        preferences.vpnWanted = false
        stopPolling()
        val intent = Intent(context, TailcatVpnService::class.java).apply {
            action = TailcatVpnService.ACTION_STOP_VPN
        }
        runCatching { context.startService(intent) }
            .onFailure { onVpnStartFailed(it.message ?: "Android could not stop the VPN service") }
    }

    fun onEngineConnected(initialMetrics: NetworkMetrics) {
        val now = unixNow()
        require(EngineHealth.shouldConnect(initialMetrics, now)) {
            "VPN engine is not live running"
        }
        _lastError.value = null
        _networkMetrics.value = initialMetrics
        _tunnelState.value = TunnelState.CONNECTED

        pollingJob?.cancel()
        pollingJob = scope.launch {
            var consecutiveFailures = 0
            while (isActive && _tunnelState.value != TunnelState.DISCONNECTED) {
                runCatching { tunnelEngine.getStats() }
                    .onSuccess { metrics ->
                        consecutiveFailures = 0
                        _networkMetrics.value = metrics
                        if (EngineHealth.shouldTearDown(metrics, unixNow())) {
                            reportError("VPN engine data plane failed")
                            stopTunnel()
                            return@launch
                        }
                        if (_tunnelState.value == TunnelState.RECONNECTING &&
                            networkMonitor.isOnline &&
                            EngineHealth.shouldConnect(metrics, unixNow())
                        ) {
                            _tunnelState.value = TunnelState.CONNECTED
                        }
                    }
                    .onFailure {
                        consecutiveFailures++
                        if (consecutiveFailures >= MAX_TELEMETRY_FAILURES) {
                            reportError(it.message ?: "Lost contact with the VPN engine")
                            stopTunnel()
                            return@launch
                        }
                    }
                delay(METRICS_POLL_INTERVAL_MS)
            }
        }
    }

    fun onVpnStopped() {
        pollingJob?.cancel()
        _tunnelState.value = TunnelState.DISCONNECTED
        _networkMetrics.value = NetworkMetrics()
        refreshPublicIp()
    }

    fun onVpnStartFailed(message: String) {
        // Resume/process recreation must not retry a start Android has rejected.
        // A new explicit Connect request sets this again after UI consent.
        preferences.vpnWanted = false
        pollingJob?.cancel()
        _tunnelState.value = TunnelState.DISCONNECTED
        _networkMetrics.value = NetworkMetrics()
        refreshPublicIp()
        reportError(message)
    }

    private fun reportError(message: String) {
        _lastError.value = message
        _tunnelEvents.tryEmit(message)
    }

    companion object {
        private const val METRICS_POLL_INTERVAL_MS = 1_000L
        private const val MAX_TELEMETRY_FAILURES = 1

        fun unixNow(): Long = System.currentTimeMillis() / 1000L
    }
}
