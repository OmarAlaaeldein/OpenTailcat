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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class TailcatVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var startJob: Job? = null
    private var metricsCollectorJob: Job? = null
    @Volatile private var shuttingDown = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_VPN) {
            TailcatApplication.instance.preferencesStore.vpnWanted = false
            serviceScope.launch { shutdown() }
            return START_NOT_STICKY
        }

        val app = TailcatApplication.instance
        val profile = app.profileRepository.activeProfile.value
        val validationError = app.tunnelController.validateStartRequest()
        if (profile == null || validationError != null) {
            rejectStart(
                validationError ?: "No gateway profile is selected"
            )
            return START_NOT_STICKY
        }

        if (startJob?.isActive == true || vpnInterface != null) return START_STICKY

        shuttingDown = false
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

            startJob = serviceScope.launch { establishAndStartEngine(profile) }
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

    private suspend fun establishAndStartEngine(profile: GatewayProfile) {
        val app = TailcatApplication.instance
        try {
            // Validate resolver IP before configuring VPN interface
            val dnsValidation = com.tailcat.vpn.core.dns.DnsValidator.validate(profile.customDns)
            check(dnsValidation is com.tailcat.vpn.core.dns.DnsValidationResult.Valid) {
                val reason = (dnsValidation as? com.tailcat.vpn.core.dns.DnsValidationResult.Invalid)?.reason ?: "unknown error"
                "Invalid DNS resolver in profile: $reason"
            }

            // Provide current validated Android LinkProperties, interface state, and DNS policy to native engine
            val networkState = org.json.JSONObject(app.networkMonitor.getNetworkStateJSON()).apply {
                put("dnsPolicy", profile.dnsPolicy.name)
                if (profile.dnsPolicy == com.tailcat.vpn.core.model.DnsPolicy.FORCED_RESOLVER) {
                    put("forcedDns", dnsValidation.ip)
                }
            }.toString()
            app.tunnelEngine.updateNetworkState(networkState)
            app.tunnelEngine.setSocketProtector { fd -> protect(fd) }

            val lockdownEnabled = if (Build.VERSION.SDK_INT >= 29) isLockdownEnabled else true
            LeakGuard.refusalReason(
                sdkInt = Build.VERSION.SDK_INT,
                lockdownEnabled = lockdownEnabled,
                splitTunnelEmpty = app.preferencesStore.splitTunnelExcludedApps.isEmpty()
            )?.let { throw IllegalStateException(it) }

            // Complete the cryptographic gateway and transport handshake before installing any
            // full-device route. A failed or cancelled prepare phase cannot affect device traffic.
            app.tunnelEngine.prepare(profile.token)
            currentCoroutineContext().ensureActive()

            val warm = vpnBuilder(profile, dnsValidation.ip, defaultRoutes = false).establish()
                ?: throw IllegalStateException("Android could not establish the VPN interface")
            vpnInterface = warm
            attachLive(app, warm, networkState)
            currentCoroutineContext().ensureActive()
            app.tunnelEngine.detachTun()

            val routed = vpnBuilder(profile, dnsValidation.ip, defaultRoutes = true).establish()
                ?: throw IllegalStateException("Android could not establish the VPN interface")
            if (routed.fd != warm.fd) {
                runCatching { warm.close() }
            }
            vpnInterface = routed
            val metrics = attachLive(app, routed, networkState)
            app.tunnelController.onEngineConnected(metrics)
            startMetricsNotificationUpdater(profile)
        } catch (error: CancellationException) {
            shutdown()
            throw error
        } catch (error: Exception) {
            app.tunnelController.onVpnStartFailed(
                error.message ?: "VPN engine failed to start"
            )
            shutdown()
        }
    }

    private suspend fun attachLive(
        app: TailcatApplication,
        established: ParcelFileDescriptor,
        networkState: String
    ): NetworkMetrics {
        currentCoroutineContext().ensureActive()
        app.tunnelEngine.attachTun(established.fd)
        app.tunnelEngine.updateNetworkState(networkState)
        val metrics = app.tunnelEngine.getStats()
        check(EngineHealth.shouldConnect(metrics, TunnelController.unixNow())) {
            "VPN engine did not become live after attach"
        }
        return metrics
    }

    private fun vpnBuilder(
        profile: GatewayProfile,
        dnsIp: String,
        defaultRoutes: Boolean
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
        if (shuttingDown) return
        shuttingDown = true
        startJob?.cancel()
        metricsCollectorJob?.cancel()
        TailcatApplication.instance.tunnelController.stopPolling()

        val tun = vpnInterface
        vpnInterface = null
        runCatching { tun?.close() }
        runCatching { TailcatApplication.instance.tunnelEngine.stop() }

        val finish = {
            TailcatApplication.instance.tunnelController.onVpnStopped()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            finish()
        } else {
            android.os.Handler(android.os.Looper.getMainLooper()).post(finish)
        }
    }

    override fun onDestroy() {
        if (!shuttingDown) {
            shutdown()
        }
        super.onDestroy()
    }

    override fun onRevoke() {
        TailcatApplication.instance.preferencesStore.vpnWanted = false
        serviceScope.launch { shutdown() }
    }

    companion object {
        const val VPN_PERMISSION_REQUIRED =
            "VPN permission is required. Tap Connect to allow the VPN connection."
        const val ACTION_START_VPN = "com.tailcat.vpn.ACTION_START"
        const val ACTION_STOP_VPN = "com.tailcat.vpn.ACTION_STOP"
    }
}
