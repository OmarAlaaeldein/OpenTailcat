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
            serviceScope.launch { shutdown() }
            return START_NOT_STICKY
        }

        val app = TailcatApplication.instance
        val profile = app.profileRepository.activeProfile.value
        val validationError = app.tunnelController.validateStartRequest()
        if (profile == null || validationError != null) {
            app.tunnelController.onVpnStartFailed(
                validationError ?: "No gateway profile is selected"
            )
            stopSelf()
            return START_NOT_STICKY
        }

        if (startJob?.isActive == true || vpnInterface != null) return START_STICKY

        shuttingDown = false
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

            // Complete the cryptographic gateway and transport handshake before installing any
            // full-device route. A failed or cancelled prepare phase cannot affect device traffic.
            app.tunnelEngine.prepare(profile.token)
            currentCoroutineContext().ensureActive()

            val routed = vpnBuilder(profile, dnsValidation.ip, defaultRoutes = true).establish()
                ?: throw IllegalStateException("Android could not establish the VPN interface")
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
            .addDnsServer(dnsIp)
            .setBlocking(true)
        for (route in VpnInterfaceSpec.defaultRoutes(defaultRoutes)) {
            builder.addRoute(route.address, route.prefixLength)
        }
        val app = TailcatApplication.instance
        for (excluded in app.preferencesStore.splitTunnelExcludedApps) {
            runCatching { builder.addDisallowedApplication(excluded) }
        }
        builder.addDisallowedApplication(packageName)
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
        serviceScope.launch { shutdown() }
    }

    companion object {
        const val ACTION_START_VPN = "com.tailcat.vpn.ACTION_START"
        const val ACTION_STOP_VPN = "com.tailcat.vpn.ACTION_STOP"
    }
}
