package com.tailcat.vpn.service

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.tailcat.vpn.TailcatApplication
import com.tailcat.vpn.core.model.GatewayProfile
import com.tailcat.vpn.core.model.NetworkMetrics
import com.tailcat.vpn.core.model.TunnelState
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class TailcatVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private val interfaceLock = Any()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startLock = Any()

    // Written by onStartCommand (main thread) and reconnectAfterFailure.
    @Volatile
    private var startJob: Job? = null
    private var metricsCollectorJob: Job? = null
    private val shuttingDown = AtomicBoolean(false)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_VPN) {
            TailcatApplication.instance.preferencesStore.vpnWanted = false
            serviceScope.launch { shutdown() }
            return START_NOT_STICKY
        }

        // A duplicate start (Always-on plus a restore, or a second tap) must not
        // be re-validated: a transient failure would tear down a live tunnel.
        if (startJob?.isActive == true || vpnInterface != null) return START_STICKY

        val app = TailcatApplication.instance
        val profile = app.profileRepository.activeProfile.value
        // Always-on starts at boot or behind a captive portal before any network
        // is validated; the start loop below retries the handshake instead.
        val validationError = app.tunnelController.validateStartRequest(requireOnline = false)
        if (profile == null || validationError != null) {
            rejectStart(
                validationError ?: "No gateway profile is selected"
            )
            return START_NOT_STICKY
        }
        // Only an explicit Connect tap fails fast; Always-on (SERVICE_INTERFACE
        // action), sticky restarts (null intent), and restores retry.
        val retryOnFailure = intent?.getBooleanExtra(EXTRA_USER_INITIATED, false) != true
        if (intent?.action == SERVICE_INTERFACE) {
            // The system started us as the Always-on VPN: the user wants it on,
            // even if this app never recorded a Connect tap.
            app.preferencesStore.vpnWanted = true
        }

        shuttingDown.set(false)
        try {
            // Sticky/always-on restores do not pass through the UI consent launcher.
            // Consent can also be revoked between the UI callback and service start.
            check(prepare(this) == null) { VPN_PERMISSION_REQUIRED }
            app.tunnelController.setTunnelState(TunnelState.CONNECTING)
            val notification = app.notificationManager.buildNotification(
                state = TunnelState.CONNECTING,
                profileName = profile.name,
                metrics = app.tunnelController.networkMetrics.value
            )
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    VpnNotificationManager.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
                )
            } else {
                startForeground(VpnNotificationManager.NOTIFICATION_ID, notification)
            }

            synchronized(startLock) {
                startJob = serviceScope.launch { startWithRetry(profile, retryOnFailure) }
            }
            return START_STICKY
        } catch (error: Exception) {
            // startForeground executes later than startForegroundService, so the
            // controller's catch cannot handle Android rejecting this service.
            rejectStart(
                error.message ?: "Android could not start the VPN service"
            )
            return START_NOT_STICKY
        }
    }

    private fun rejectStart(message: String) {
        val app = TailcatApplication.instance
        app.tunnelController.onVpnStartFailed(message)
        // Android can crash the process even when stopSelf is called immediately
        // after a rejected startForegroundService request. Satisfy that request
        // with a short-lived notification, which needs no VPN consent, then stop.
        // This type is used only for rejection cleanup, never to run a tunnel.
        runCatching {
            val notification = app.notificationManager.buildNotification(
                state = TunnelState.DISCONNECTED,
                profileName = app.profileRepository.activeProfile.value?.name.orEmpty(),
                metrics = app.tunnelController.networkMetrics.value
            )
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    VpnNotificationManager.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
                )
            } else {
                startForeground(VpnNotificationManager.NOTIFICATION_ID, notification)
            }
        }
        shutdown()
    }

    /**
     * Runs start attempts until one connects. A failure ends the service when
     * the start was a Connect tap, the cause is permanent, or the user no
     * longer wants the VPN; otherwise the engine is reset and the attempt is
     * retried with [StartRetry] backoff while vpnWanted stays set.
     */
    private suspend fun startWithRetry(profile: GatewayProfile, retryOnFailure: Boolean) {
        val app = TailcatApplication.instance
        // A stop launched off the main thread must finish before prepare, or
        // it would tear down the session this start creates.
        pendingNativeStop?.join()
        var attempt = 0
        while (true) {
            try {
                establishAndStartEngine(profile)
                return
            } catch (error: CancellationException) {
                shutdown()
                throw error
            } catch (error: Exception) {
                val message = error.message ?: "VPN engine failed to start"
                val permanent = error is PermanentStartFailure ||
                    StartRetry.isPermanentEngineError(message) ||
                    prepare(this) != null
                if (!retryOnFailure || permanent || !app.preferencesStore.vpnWanted) {
                    app.tunnelController.onVpnStartFailed(message)
                    shutdown()
                    return
                }
                attempt++
                val waitMs = StartRetry.delayMs(attempt)
                resetForRetry()
                app.tunnelController.onStartRetrying(message, waitMs)
                showStateNotification(profile, TunnelState.RECONNECTING)
                // Cancelled by shutdown() when the user disconnects.
                delay(waitMs)
            }
        }
    }

    /** Drops any TUN adopted by the failed attempt and the native session. */
    private fun resetForRetry() {
        val tun = synchronized(interfaceLock) {
            val current = vpnInterface
            vpnInterface = null
            current
        }
        runCatching { tun?.close() }
        runCatching { TailcatApplication.instance.tunnelEngine.stop() }
    }

    private fun showStateNotification(profile: GatewayProfile, state: TunnelState) {
        val app = TailcatApplication.instance
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching {
            manager.notify(
                VpnNotificationManager.NOTIFICATION_ID,
                app.notificationManager.buildNotification(
                    state = state,
                    profileName = profile.name,
                    metrics = app.tunnelController.networkMetrics.value,
                    detail = if (state == TunnelState.RECONNECTING) {
                        app.tunnelController.lastError.value
                    } else {
                        null
                    }
                )
            )
        }
    }

    /**
     * The live session failed (pump exit, stale engine health, or a silent
     * gateway). The user still wants the VPN, so drop the session and start
     * again with [StartRetry] backoff. The foreground notification stays up
     * and names the cause. Without Always-on lockdown, traffic uses the
     * device's own network until the new session's routes are installed.
     */
    private fun reconnectAfterFailure() {
        val app = TailcatApplication.instance
        synchronized(startLock) {
            if (shuttingDown.get()) return
            // The start that connected this session may still be returning;
            // run after it rather than dropping the reconnect.
            val previous = startJob
            startJob = serviceScope.launch {
                previous?.join()
                metricsCollectorJob?.cancel()
                resetForRetry()
                val profile = app.profileRepository.activeProfile.value
                val validationError = app.tunnelController.validateStartRequest(requireOnline = false)
                if (profile == null || validationError != null) {
                    app.tunnelController.onVpnStartFailed(
                        validationError ?: "No gateway profile is selected"
                    )
                    shutdown()
                    return@launch
                }
                showStateNotification(profile, TunnelState.RECONNECTING)
                startWithRetry(profile, retryOnFailure = true)
            }
        }
    }

    /** One start attempt. Throws on failure after closing any TUN it still owns. */
    private suspend fun establishAndStartEngine(profile: GatewayProfile) {
        val app = TailcatApplication.instance
        var warmOwned: ParcelFileDescriptor? = null
        var routedOwned: ParcelFileDescriptor? = null
        try {
            // Validate resolver IP before configuring VPN interface
            val dnsValidation = com.tailcat.vpn.core.dns.DnsValidator.validate(profile.customDns)
            if (dnsValidation !is com.tailcat.vpn.core.dns.DnsValidationResult.Valid) {
                val reason = (dnsValidation as? com.tailcat.vpn.core.dns.DnsValidationResult.Invalid)?.reason ?: "unknown error"
                throw PermanentStartFailure("Invalid DNS resolver in profile: $reason")
            }

            // Provide current validated Android LinkProperties, interface state, and DNS policy to native engine
            val networkState = org.json.JSONObject(app.networkMonitor.getNetworkStateJSON()).apply {
                put("dnsPolicy", profile.dnsPolicy.name)
                put("tunnelMtu", profile.mtu)
                if (profile.dnsPolicy == com.tailcat.vpn.core.model.DnsPolicy.FORCED_RESOLVER) {
                    put("forcedDns", dnsValidation.ip)
                }
            }.toString()
            app.tunnelEngine.updateNetworkState(networkState)
            app.tunnelEngine.setSocketProtector { fd -> protect(fd) }

            // Complete the cryptographic gateway and transport handshake before installing any
            // full-device route. A failed or cancelled prepare phase cannot affect device traffic.
            // Always-on / block-without-VPN is recommended but optional (does not gate Connect).
            app.tunnelEngine.prepare(profile.token)
            // Tailcat createEngine disables netns; re-enable Android VpnService.protect hooks
            // before any default route exists so Magicsock/DERP redials bypass the TUN (H2).
            app.tunnelEngine.ensureTransportProtect()
            // Magicsock/DERP sockets opened during prepare still lack Control; protect them now.
            protectOpenTransportSockets()
            currentCoroutineContext().ensureActive()
            checkNotShuttingDown()

            // Prepare measured gateway IPv6 egress; refuse a resolver it cannot reach
            // before any route or DNS server is installed.
            com.tailcat.vpn.core.dns.DnsValidator.gatewayRejection(
                dnsValidation,
                app.tunnelEngine.getStats().ipv6Egress
            )?.let { throw PermanentStartFailure(it) }

            // Snapshot once so warm and routed interfaces exclude the same apps.
            val excludedApps = resolveExcludedApplications()

            val warm = vpnBuilder(profile, dnsValidation.ip, defaultRoutes = false, excludedApps)
                .establish()
                ?: throw IllegalStateException("Android could not establish the VPN interface")
            warmOwned = warm
            adoptInterface(warm)
            warmOwned = null
            attachLive(app, warm, networkState)
            // Warm attach may open more transport sockets; protect before default routes.
            protectOpenTransportSockets(excludeTun = warm)
            currentCoroutineContext().ensureActive()
            checkNotShuttingDown()
            // Intentional two-phase rebind: disarm pump-failure so the planned
            // detach cannot race a FAILED mark before the routed TUN attaches.
            app.tunnelEngine.disarmPumps()
            app.tunnelEngine.detachTun()
            app.tunnelEngine.ensureTransportProtect()
            protectOpenTransportSockets()

            val routed = vpnBuilder(profile, dnsValidation.ip, defaultRoutes = true, excludedApps)
                .establish()
                ?: throw IllegalStateException("Android could not establish the VPN interface")
            routedOwned = routed
            if (routed.fd != warm.fd) {
                clearInterfaceIf(warm)
                runCatching { warm.close() }
            }
            adoptInterface(routed)
            routedOwned = null
            val metrics = attachLive(app, routed, networkState)
            // After default routes, re-sweep so any late Magicsock/DERP redial is protected.
            app.tunnelEngine.ensureTransportProtect()
            protectOpenTransportSockets(excludeTun = routed)
            app.tunnelController.sessionFailureHandler = { _ -> reconnectAfterFailure() }
            app.tunnelController.onEngineConnected(metrics)
            startMetricsNotificationUpdater(profile)
        } catch (error: Throwable) {
            closeOwned(warmOwned, routedOwned)
            throw error
        }
    }

    private fun closeOwned(vararg fds: ParcelFileDescriptor?) {
        for (fd in fds) {
            if (fd != null) {
                runCatching { fd.close() }
            }
        }
    }

    private fun checkNotShuttingDown() {
        if (shuttingDown.get()) {
            throw CancellationException("VPN shutdown in progress")
        }
    }

    private fun adoptInterface(established: ParcelFileDescriptor) {
        synchronized(interfaceLock) {
            checkNotShuttingDown()
            vpnInterface = established
        }
    }

    private fun clearInterfaceIf(expected: ParcelFileDescriptor) {
        synchronized(interfaceLock) {
            if (vpnInterface === expected || vpnInterface?.fd == expected.fd) {
                vpnInterface = null
            }
        }
    }

    private suspend fun attachLive(
        app: TailcatApplication,
        established: ParcelFileDescriptor,
        networkState: String
    ): NetworkMetrics {
        currentCoroutineContext().ensureActive()
        checkNotShuttingDown()
        app.tunnelEngine.attachTun(established.fd)
        app.tunnelEngine.updateNetworkState(networkState)
        val metrics = app.tunnelEngine.getStats()
        check(EngineHealth.shouldConnect(metrics, TunnelController.unixNow())) {
            "VPN engine did not become live after attach"
        }
        return metrics
    }

    /**
     * Excluded apps bypass the VPN by design (split tunneling, not leak-free);
     * see [SplitTunnelExclusions]. Stale package entries are skipped.
     */
    private fun resolveExcludedApplications(): List<String> =
        SplitTunnelExclusions.validPackages(
            TailcatApplication.instance.preferencesStore.splitTunnelExcludedApps
        ) { pkg ->
            runCatching { packageManager.getPackageInfo(pkg, 0) }.isSuccess
        }

    private fun vpnBuilder(
        profile: GatewayProfile,
        dnsIp: String,
        defaultRoutes: Boolean,
        excludedApps: List<String>
    ): Builder {
        val builder = Builder()
            .setSession("OpenTailcat - ${profile.name}")
            .setMtu(profile.mtu)
            .addAddress(VpnInterfaceSpec.IPV4_ADDRESS, VpnInterfaceSpec.IPV4_PREFIX)
            .addAddress(VpnInterfaceSpec.IPV6_ADDRESS, VpnInterfaceSpec.IPV6_PREFIX)
            .setBlocking(true)
        if (Build.VERSION.SDK_INT >= 29) {
            builder.setMetered(false)
        }
        for (pkg in excludedApps) {
            builder.addDisallowedApplication(pkg)
        }
        if (defaultRoutes) {
            builder.addDnsServer(dnsIp)
        }
        for (route in VpnInterfaceSpec.defaultRoutes(defaultRoutes)) {
            builder.addRoute(route.address, route.prefixLength)
        }
        return builder
    }

    private fun startMetricsNotificationUpdater(profile: GatewayProfile) {
        val app = TailcatApplication.instance
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        metricsCollectorJob?.cancel()
        metricsCollectorJob = serviceScope.launch {
            app.tunnelController.networkMetrics.collectLatest { metrics ->
                manager.notify(
                    VpnNotificationManager.NOTIFICATION_ID,
                    app.notificationManager.buildNotification(
                        state = app.tunnelController.tunnelState.value,
                        profileName = profile.name,
                        metrics = metrics
                    )
                )
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
    }

    private fun shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) return
        TailcatApplication.instance.tunnelController.sessionFailureHandler = null
        synchronized(startLock) { startJob?.cancel() }
        metricsCollectorJob?.cancel()
        TailcatApplication.instance.tunnelController.stopPolling()

        // Closing the TUN must not race a concurrent establish assigning a new FD.
        val tun = synchronized(interfaceLock) {
            val current = vpnInterface
            vpnInterface = null
            current
        }
        runCatching { tun?.close() }

        val finish = {
            TailcatApplication.instance.tunnelController.onVpnStopped()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            // Native stop can wait up to its bound; never block the main thread
            // (onDestroy, rejected starts). The TUN is already closed, so routes
            // are gone before this runs; the next start joins it first.
            pendingNativeStop = serviceScope.launch {
                runCatching { TailcatApplication.instance.tunnelEngine.stop() }
            }
            finish()
        } else {
            runCatching { TailcatApplication.instance.tunnelEngine.stop() }
            android.os.Handler(android.os.Looper.getMainLooper()).post(finish)
        }
    }

    override fun onDestroy() {
        if (!shuttingDown.get()) {
            shutdown()
        }
        super.onDestroy()
    }

    override fun onRevoke() {
        TailcatApplication.instance.preferencesStore.vpnWanted = false
        serviceScope.launch { shutdown() }
    }


    /**
     * Protect already-open process sockets so Magicsock/DERP traffic bypasses the TUN.
     * See [TransportSocketProtect]. Excludes the active VPN interface FD when known.
     */
    private fun protectOpenTransportSockets(excludeTun: ParcelFileDescriptor? = null) {
        val exclude = linkedSetOf<Int>()
        excludeTun?.fd?.takeIf { it >= 0 }?.let { exclude.add(it) }
        synchronized(interfaceLock) {
            vpnInterface?.fd?.takeIf { it >= 0 }?.let { exclude.add(it) }
        }
        val names = runCatching { File("/proc/self/fd").list() }.getOrNull()
        val fds = TransportSocketProtect.listCandidateFds(names, exclude)
        TransportSocketProtect.protectAll(fds) { fd -> protect(fd) }
    }

    companion object {
        const val VPN_PERMISSION_REQUIRED =
            "VPN permission is required. Tap Connect to allow the VPN connection."
        const val ACTION_START_VPN = "com.tailcat.vpn.ACTION_START"
        const val ACTION_STOP_VPN = "com.tailcat.vpn.ACTION_STOP"
        const val EXTRA_USER_INITIATED = "com.tailcat.vpn.extra.USER_INITIATED"

        /** Native stop running off the main thread; outlives the service instance. */
        @Volatile
        private var pendingNativeStop: Job? = null
    }
}
