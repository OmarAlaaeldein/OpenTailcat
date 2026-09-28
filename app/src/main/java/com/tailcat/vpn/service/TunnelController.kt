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

    // Reported by the running VPN service; null while it is not running.
    private val _alwaysOnStatus = MutableStateFlow<AlwaysOnStatus?>(null)
    val alwaysOnStatus: StateFlow<AlwaysOnStatus?> = _alwaysOnStatus.asStateFlow()

    fun setAlwaysOnStatus(status: AlwaysOnStatus?) {
        _alwaysOnStatus.value = status
    }

    // Split-tunnel exclusions the running session applied; null while stopped.
    private val _appliedExclusions = MutableStateFlow<Set<String>?>(null)
    val appliedExclusions: StateFlow<Set<String>?> = _appliedExclusions.asStateFlow()

    fun setAppliedExclusions(packages: Set<String>?) {
        _appliedExclusions.value = packages
    }

    val engineAvailability: EngineAvailability
        get() = tunnelEngine.availability

    private var pollingJob: Job? = null

    /**
     * Set by the VPN service while it owns a session. The poll loop hands an
     * engine-initiated failure to it, so the service reconnects with backoff
     * instead of tearing the VPN down for good.
     */
    @Volatile
    var sessionFailureHandler: ((String) -> Unit)? = null

    /** True from [onEngineConnected] until that session ends or fails. */
    @Volatile
    private var sessionLive = false

    init {
        scope.launch { ipAuditor.fetchCurrentEgress() }

        networkMonitor.setOnNetworkStateChangedListener { networkType, stateJson ->
            // Never invoke JNI/Go on the NetworkCallback thread synchronously.
            scope.launch(Dispatchers.IO) {
                tunnelEngine.updateNetworkState(stateJson)
            }
            when {
                networkType == NetworkType.NONE && _tunnelState.value == TunnelState.CONNECTED -> {
                    _tunnelState.value = TunnelState.RECONNECTING
                }
                // Only a live session may return to CONNECTED; RECONNECTING also
                // covers a service restart that has not finished its routed TUN.
                networkType != NetworkType.NONE && _tunnelState.value == TunnelState.RECONNECTING &&
                    sessionLive -> {
                    scope.launch(Dispatchers.IO) {
                        runCatching { tunnelEngine.getStats() }
                            .onSuccess { metrics ->
                                _networkMetrics.value = metrics
                                if (sessionLive && EngineHealth.shouldConnect(metrics, unixNow())) {
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

    /**
     * Returns why a start cannot proceed, or null. [requireOnline] is for
     * explicit Connect taps; service starts (Always-on at boot, captive
     * portals, restores) must not need a validated network, because the
     * service retries the handshake until the network is usable.
     */
    fun validateStartRequest(requireOnline: Boolean = true): String? {
        val profile = profileRepository.activeProfile.value
            ?: return "Pair a gateway token before connecting"
        if (requireOnline && !networkMonitor.isOnline) {
            return "No validated internet connection is available"
        }

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

    /**
     * Called when the UI starts or resumes. Only the VPN service promotes a
     * session to CONNECTED: promoting from engine stats here once declared
     * CONNECTED during the host-routes-only startup phase. This only restores
     * a VPN the user still wants but that is not running.
     */
    fun onUiResumed() {
        if (_tunnelState.value != TunnelState.DISCONNECTED) return
        scope.launch { restoreIfWanted() }
    }

    fun restoreIfWanted(): Boolean {
        if (!VpnRestore.shouldRestore(
                vpnWanted = preferences.vpnWanted,
                state = _tunnelState.value,
                validationError = validateStartRequest(requireOnline = false)
            )
        ) {
            return false
        }
        return startTunnel(userInitiated = false)
    }

    /**
     * [userInitiated] starts come from the Connect button: they need a
     * validated network and report a failed handshake instead of retrying.
     */
    fun startTunnel(userInitiated: Boolean = true): Boolean {
        val error = validateStartRequest(requireOnline = userInitiated)
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
                putExtra(TailcatVpnService.EXTRA_USER_INITIATED, userInitiated)
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
        sessionLive = true

        pollingJob?.cancel()
        pollingJob = scope.launch {
            var consecutiveFailures = 0
            var consecutiveStaleHealth = 0
            while (isActive && _tunnelState.value != TunnelState.DISCONNECTED) {
                runCatching { tunnelEngine.getStats() }
                    .onSuccess { metrics ->
                        consecutiveFailures = 0
                        _networkMetrics.value = metrics
                        val reason = EngineHealth.teardownReason(metrics, unixNow())
                        consecutiveStaleHealth = EngineHealth.nextStalePollCount(
                            reason,
                            consecutiveStaleHealth
                        )
                        val tearDown = when (reason) {
                            EngineHealth.TeardownReason.Healthy -> false
                            is EngineHealth.TeardownReason.HealthStale ->
                                EngineHealth.stalePollsRequireTeardown(consecutiveStaleHealth)
                            else -> true // PumpFailed / TransportLost: fail closed immediately
                        }
                        if (tearDown) {
                            onSessionFailed(dataPlaneFailureMessage(reason, metrics))
                            return@launch
                        }
                        if (reason == EngineHealth.TeardownReason.Healthy) {
                            // Pumps live but live DiscoPing is stale: degraded, still usable.
                            _tunnelState.value = if (metrics.discoStale) {
                                TunnelState.DEGRADED
                            } else {
                                TunnelState.CONNECTED
                            }
                        } else if (_tunnelState.value == TunnelState.RECONNECTING &&
                            networkMonitor.isOnline &&
                            EngineHealth.shouldConnect(metrics, unixNow())
                        ) {
                            _tunnelState.value = TunnelState.CONNECTED
                        }
                    }
                    .onFailure {
                        consecutiveFailures++
                        if (consecutiveFailures >= MAX_TELEMETRY_FAILURES) {
                            val base = "Lost contact with the VPN engine"
                            val detail = it.message ?: "no detail"
                            onSessionFailed(
                                if (preferences.debugMode) {
                                    "$base ($consecutiveFailures consecutive failure(s); last: $detail)"
                                } else {
                                    base
                                }
                            )
                            return@launch
                        }
                    }
                delay(METRICS_POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * An engine-initiated failure ended the live session. While the user
     * still wants the VPN and the service owns the session, the service
     * reconnects with backoff (the notification stays up and names the
     * cause); otherwise the VPN stops as before.
     */
    private fun onSessionFailed(cause: String) {
        sessionLive = false
        reportError(cause)
        val handler = sessionFailureHandler
        if (handler != null && preferences.vpnWanted) {
            _tunnelState.value = TunnelState.RECONNECTING
            _networkMetrics.value = NetworkMetrics()
            handler(cause)
        } else {
            stopTunnel()
        }
    }

    fun onVpnStopped() {
        sessionLive = false
        pollingJob?.cancel()
        _tunnelState.value = TunnelState.DISCONNECTED
        _networkMetrics.value = NetworkMetrics()
        refreshPublicIp()
    }

    /**
     * A non-UI start failed and the service will retry in [retryInMs]. The
     * user still wants the VPN, so vpnWanted stays set; the error is shown
     * without a new event per attempt.
     */
    fun onStartRetrying(message: String, retryInMs: Long) {
        sessionLive = false
        pollingJob?.cancel()
        _tunnelState.value = TunnelState.RECONNECTING
        _networkMetrics.value = NetworkMetrics()
        _lastError.value = "${message.trimEnd('.')}. Retrying in ${retryInMs / 1000} s."
    }

    fun onVpnStartFailed(message: String) {
        // Resume/process recreation must not retry a start Android has rejected.
        // A new explicit Connect request sets this again after UI consent.
        preferences.vpnWanted = false
        sessionLive = false
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

    /**
     * User-facing data-plane failure: always names the cause; with debug mode
     * on, appends a raw telemetry snapshot so failures are diagnosable from
     * the banner alone. Debug output never changes routing or lockdown.
     */
    private fun dataPlaneFailureMessage(
        reason: EngineHealth.TeardownReason,
        metrics: NetworkMetrics
    ): String {
        val base = "VPN data plane failed: ${EngineHealth.shortCause(reason)}"
        if (!preferences.debugMode) return base
        val drops = metrics.dropCounters
        val healthAge = (unixNow() - metrics.healthUnixSec).coerceAtLeast(0L)
        return base + " [debug state=${metrics.state}" +
            " transport=${metrics.transportType} tcpOnly=${metrics.tcpOnly} ipv6Egress=${metrics.ipv6Egress}" +
            " healthAge=${healthAge}s rtt=${metrics.rttLatencyMs}ms" +
            " dns=${metrics.dnsQueries} tcp=${metrics.tcpPackets} udp=${metrics.udpPackets}" +
            " drops=${drops.malformedIp}/${drops.mtuExceeded}/${drops.queueExhaustion}/${drops.policyRejections}" +
            "/${drops.linkQueueDrops}/${drops.udpBufferDrops}" +
            " egressErr=${metrics.egressAuditError ?: "-"}]"
    }

    companion object {
        private const val METRICS_POLL_INTERVAL_MS = 1_000L
        // One failed JNI stats read is not a dead engine; three in a row is.
        private const val MAX_TELEMETRY_FAILURES = 3

        fun unixNow(): Long = System.currentTimeMillis() / 1000L
    }
}
