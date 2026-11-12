package com.tailcat.vpn.service

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.tailcat.vpn.TailcatApplication
import com.tailcat.vpn.core.dns.DnsValidationResult
import com.tailcat.vpn.core.dns.DnsValidator
import com.tailcat.vpn.core.model.DnsPolicy
import com.tailcat.vpn.core.model.GatewayProfile
import com.tailcat.vpn.core.model.NetworkMetrics
import com.tailcat.vpn.core.model.TunnelState
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

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

    /**
     * Incremented by every shutdown() (under [interfaceLock]). A start job
     * records the epoch it was launched in and is stale once it changes, even
     * if a later Connect on this instance has cleared [shuttingDown].
     */
    private val shutdownEpoch = AtomicLong(0)

    /**
     * Starts this instance accepted. Incremented with shuttingDown.set(false)
     * under [interfaceLock] (main thread only), so shutdown() can be scoped to
     * the starts before a STOP intent and finish() can tell that a START was
     * accepted after its CAS (TLA+ finding N1b).
     */
    @Volatile
    private var startGeneration = 0L

    /** startGeneration of the last shutdown whose finish() ran (main thread). */
    private var finishedGeneration = 0L

    /** startId of the last intent handled (main thread), for stopSelf(startId). */
    private var lastStartId = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (intent?.action == ACTION_STOP_VPN) {
            handleStopIntent(startId)
            return START_NOT_STICKY
        }

        // A duplicate start (Always-on plus a restore, or a second tap) must not
        // be re-validated: a transient failure would tear down a live tunnel.
        if (!shuttingDown.get() && (startJob?.isActive == true || vpnInterface != null)) return START_STICKY

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

        synchronized(interfaceLock) {
            startGeneration++
            shuttingDown.set(false)
        }
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
                val epoch = shutdownEpoch.get()
                startJob = launchSerializedStart { startWithRetry(profile, retryOnFailure, epoch) }
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

    /**
     * ACTION_STOP_VPN, sent only by stopTunnel(), which clears vpnWanted first.
     * The intent can reach a different instance than the session it meant to
     * stop, or arrive after a newer Connect (TLA+ findings F7, N1b), so it only
     * stops the starts this instance accepted before it.
     */
    private fun handleStopIntent(startId: Int) {
        when {
            // Nothing started here since the last finished shutdown (a fresh
            // instance, or one a queued intent kept alive). Android keeps the
            // service if a newer start is already queued for it.
            startGeneration == finishedGeneration -> stopSelf(startId)
            // A shutdown is in flight; its finish() ends the service.
            shuttingDown.get() -> Unit
            // vpnWanted is set again: a Connect followed the Disconnect that
            // sent this intent. Never write vpnWanted here (F7).
            TailcatApplication.instance.preferencesStore.vpnWanted -> Unit
            else -> {
                val generation = startGeneration
                serviceScope.launch { shutdown(startGen = generation) }
            }
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
    private suspend fun startWithRetry(profile: GatewayProfile, retryOnFailure: Boolean, epoch: Long) {
        val app = TailcatApplication.instance
        // A stop launched off the main thread must finish before prepare, or
        // it would tear down the session this start creates.
        pendingNativeStop?.join()
        var attempt = 0
        while (true) {
            try {
                establishAndStartEngine(profile, epoch)
                return
            } catch (error: CancellationException) {
                abandonStaleStart(epoch)
                throw error
            } catch (error: Exception) {
                if (!currentCoroutineContext().isActive || isStale(epoch)) {
                    // A JNI call cancelled by shutdown() returns a plain error
                    // ("context canceled"). This job is stale: the user already
                    // disconnected and may have connected again, so it must not
                    // clear vpnWanted, reset the UI, or retry (TLA+ finding F4).
                    abandonStaleStart(epoch)
                    throw CancellationException("VPN start superseded by shutdown")
                }
                val message = error.message ?: "VPN engine failed to start"
                val permanent = error is PermanentStartFailure ||
                    StartRetry.isPermanentEngineError(message) ||
                    prepare(this) != null
                if (!retryOnFailure || permanent || !app.preferencesStore.vpnWanted) {
                    if (!failStart(epoch, message)) {
                        abandonStaleStart(epoch)
                        throw CancellationException("VPN start superseded by shutdown")
                    }
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

    /**
     * A start that shutdown() cancelled may still have created a native
     * session (prepare returned after shutdown's own stop, TLA+ finding F2).
     * Stop it. Safe because start jobs are serialized: no newer start runs
     * until this job completes. shutdown() is a no-op if it already ran.
     */
    private fun abandonStaleStart(epoch: Long) {
        runCatching { TailcatApplication.instance.tunnelEngine.stop() }
        // Only a start of the current epoch may end the service; an older
        // one's shutdown already ran, and a newer start may own the service now.
        shutdown(epoch)
    }

    /**
     * Reports a failed start of [epoch] and ends the service, unless a
     * shutdown superseded the start (then returns false and does nothing).
     * The staleness check and onVpnStartFailed run under interfaceLock, which
     * shutdown() takes for its CAS: a Disconnect and a new Connect cannot land
     * between them, so a stale failure never clears the new Connect's
     * vpnWanted (TLA+ finding N2).
     */
    private fun failStart(epoch: Long, message: String): Boolean {
        val current = synchronized(interfaceLock) {
            if (isStale(epoch)) {
                false
            } else {
                TailcatApplication.instance.tunnelController
                    .onVpnStartFailed(message, serviceStopping = true)
                true
            }
        }
        if (current) shutdown(epoch)
        return current
    }

    private fun isStale(epoch: Long): Boolean =
        shuttingDown.get() || shutdownEpoch.get() != epoch

    /**
     * Launches a start job that first waits for every earlier start job of any
     * service instance, so a cancelled job finishing a blocking JNI call can
     * never act on (or stop) the session of a newer start.
     */
    private fun launchSerializedStart(block: suspend () -> Unit): Job =
        synchronized(START_SERIAL) {
            val previous = lastStartJob
            serviceScope.launch {
                previous?.join()
                block()
            }.also { lastStartJob = it }
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
            val epoch = shutdownEpoch.get()
            startJob = launchSerializedStart {
                if (isStale(epoch)) return@launchSerializedStart
                metricsCollectorJob?.cancel()
                resetForRetry()
                val profile = app.profileRepository.activeProfile.value
                val validationError = app.tunnelController.validateStartRequest(requireOnline = false)
                if (profile == null || validationError != null) {
                    if (!failStart(epoch, validationError ?: "No gateway profile is selected")) {
                        abandonStaleStart(epoch)
                    }
                    return@launchSerializedStart
                }
                showStateNotification(profile, TunnelState.RECONNECTING)
                startWithRetry(profile, retryOnFailure = true, epoch)
            }
        }
    }

    /**
     * One start attempt. Every TUN it establishes is adopted as vpnInterface
     * the moment it exists, so the retry and shutdown paths close it.
     */
    private suspend fun establishAndStartEngine(profile: GatewayProfile, epoch: Long) {
        val app = TailcatApplication.instance
        // Validate resolver IP before configuring VPN interface
        val dnsValidation = DnsValidator.validate(profile.customDns)
        if (dnsValidation !is DnsValidationResult.Valid) {
            val reason = (dnsValidation as? DnsValidationResult.Invalid)?.reason ?: "unknown error"
            throw PermanentStartFailure("Invalid DNS resolver in profile: $reason")
        }

        // Provide current validated Android LinkProperties, interface state, and DNS policy to native engine
        val networkState = JSONObject(app.networkMonitor.getNetworkStateJSON()).apply {
            put("dnsPolicy", profile.dnsPolicy.name)
            put("tunnelMtu", profile.mtu)
            // Upstream engine logs name public/local IPs and peer endpoints.
            put("verboseLogs", app.preferencesStore.debugMode)
            if (profile.dnsPolicy == DnsPolicy.FORCED_RESOLVER) {
                put("forcedDns", dnsValidation.ip)
            }
        }.toString()
        app.tunnelEngine.updateNetworkState(networkState)
        app.tunnelEngine.setSocketProtector { fd -> protect(fd) }

        // Last check before a native session exists (TLA+ finding F2).
        currentCoroutineContext().ensureActive()
        checkNotShuttingDown(epoch)

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
        checkNotShuttingDown(epoch)

        // Prepare measured gateway IPv6 egress; refuse a resolver it cannot reach
        // before any route or DNS server is installed.
        DnsValidator.gatewayRejection(
            dnsValidation,
            app.tunnelEngine.getStats().ipv6Egress
        )?.let { throw PermanentStartFailure(it) }

        // Snapshot once so warm and routed interfaces exclude the same apps.
        val excludedApps = resolveExcludedApplications()

        val warm = establishAndAdopt(
            vpnBuilder(profile, dnsValidation.ip, defaultRoutes = false, excludedApps),
            epoch
        )
        attachLive(app, warm, networkState, epoch)
        // Warm attach may open more transport sockets; protect before default routes.
        protectOpenTransportSockets(excludeTun = warm)
        currentCoroutineContext().ensureActive()
        checkNotShuttingDown(epoch)
        // Intentional two-phase rebind: disarm pump-failure so the planned
        // detach cannot race a FAILED mark before the routed TUN attaches.
        app.tunnelEngine.disarmPumps()
        app.tunnelEngine.detachTun()
        app.tunnelEngine.ensureTransportProtect()
        protectOpenTransportSockets()

        // Default routes are installed only while no shutdown is under way;
        // a shutdown that starts later finds and closes this TUN (F3).
        val routed = establishAndAdopt(
            vpnBuilder(profile, dnsValidation.ip, defaultRoutes = true, excludedApps),
            epoch
        )
        if (routed.fd != warm.fd) {
            runCatching { warm.close() }
        }
        val metrics = attachLive(app, routed, networkState, epoch)
        // After default routes, re-sweep so any late Magicsock/DERP redial is protected.
        app.tunnelEngine.ensureTransportProtect()
        protectOpenTransportSockets(excludeTun = routed)
        // Publish CONNECTED atomically with respect to shutdown(), whose
        // check-and-set of shuttingDown also takes interfaceLock: either
        // shutdown already started and nothing is published, or it runs
        // afterwards and clears all of it (TLA+ finding F5).
        synchronized(interfaceLock) {
            checkNotShuttingDown(epoch)
            app.tunnelController.sessionFailureHandler = { _ -> reconnectAfterFailure() }
            app.tunnelController.onEngineConnected(metrics)
            app.tunnelController.setAppliedExclusions(excludedApps.toSet())
            publishAlwaysOnStatus()
            startMetricsNotificationUpdater(profile)
        }
    }

    private fun checkNotShuttingDown(epoch: Long) {
        if (isStale(epoch)) {
            throw CancellationException("VPN shutdown in progress")
        }
    }

    /**
     * Establishes the interface and adopts it as [vpnInterface] under
     * [interfaceLock], after checking that no shutdown has started. shutdown()
     * sets shuttingDown under the same lock, so a TUN (and its routes) can
     * exist only while shutdown either has not started or will close it.
     */
    private fun establishAndAdopt(builder: Builder, epoch: Long): ParcelFileDescriptor =
        synchronized(interfaceLock) {
            checkNotShuttingDown(epoch)
            val established = builder.establish()
                ?: throw IllegalStateException("Android could not establish the VPN interface")
            vpnInterface = established
            established
        }

    private suspend fun attachLive(
        app: TailcatApplication,
        established: ParcelFileDescriptor,
        networkState: String,
        epoch: Long
    ): NetworkMetrics {
        currentCoroutineContext().ensureActive()
        checkNotShuttingDown(epoch)
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
            var lastState: TunnelState? = null
            var lastPostMs = 0L
            app.tunnelController.networkMetrics.collect { metrics ->
                val state = app.tunnelController.tunnelState.value
                val now = SystemClock.elapsedRealtime()
                if (!NotificationThrottle.shouldPost(state, lastState, now, lastPostMs)) return@collect
                lastState = state
                lastPostMs = now
                manager.notify(
                    VpnNotificationManager.NOTIFICATION_ID,
                    app.notificationManager.buildNotification(
                        state = state,
                        profileName = profile.name,
                        metrics = metrics
                    )
                )
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
    }

    /** isLockdownEnabled needs a live VPN network, so read it once routes are up. */
    private fun publishAlwaysOnStatus() {
        if (Build.VERSION.SDK_INT < LockdownProbe.STATUS_API) return
        val status = runCatching { AlwaysOnStatus(isAlwaysOn, isLockdownEnabled) }.getOrNull()
        TailcatApplication.instance.tunnelController.setAlwaysOnStatus(status)
    }

    /**
     * Ends the service. With [epoch], only if no shutdown happened since that
     * epoch; with [startGen], only if this instance accepted no start since
     * that generation. Both are checked in the same locked step as the CAS,
     * so a stale start job or STOP intent cannot end a service that a newer
     * Connect owns.
     */
    private fun shutdown(epoch: Long? = null, startGen: Long? = null) {
        // Under interfaceLock so establishAndAdopt and the CONNECTED publish
        // in establishAndStartEngine are atomic with respect to it.
        val casGeneration = synchronized(interfaceLock) {
            val proceed = (epoch == null || shutdownEpoch.get() == epoch) &&
                (startGen == null || startGeneration == startGen) &&
                shuttingDown.compareAndSet(false, true)
            if (proceed) {
                shutdownEpoch.incrementAndGet()
                startGeneration
            } else {
                -1L
            }
        }
        if (casGeneration < 0) return
        TailcatApplication.instance.tunnelController.sessionFailureHandler = null
        TailcatApplication.instance.tunnelController.setAlwaysOnStatus(null)
        TailcatApplication.instance.tunnelController.setAppliedExclusions(null)
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

        val finish: () -> Unit = finish@{
            // A START accepted after the CAS owns this instance now; stopping
            // here would cancel it through onDestroy (TLA+ finding N1b).
            if (startGeneration != casGeneration) return@finish
            finishedGeneration = casGeneration
            TailcatApplication.instance.tunnelController.onVpnStopped()
            stopForeground(STOP_FOREGROUND_REMOVE)
            // Not stopSelf(): a start Android already queued for this instance
            // must keep it alive and reach onStartCommand.
            stopSelf(lastStartId)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // Native stop can wait up to its bound; never block the main thread
            // (onDestroy, rejected starts). The TUN is already closed, so routes
            // are gone before this runs; the next start joins it first.
            pendingNativeStop = serviceScope.launch {
                runCatching { TailcatApplication.instance.tunnelEngine.stop() }
            }
            finish()
        } else {
            runCatching { TailcatApplication.instance.tunnelEngine.stop() }
            Handler(Looper.getMainLooper()).post(finish)
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

        private val START_SERIAL = Any()

        /** Most recent start job of any service instance; the next one joins it. */
        @Volatile
        private var lastStartJob: Job? = null

        /** Native stop running off the main thread; outlives the service instance. */
        @Volatile
        private var pendingNativeStop: Job? = null
    }
}
