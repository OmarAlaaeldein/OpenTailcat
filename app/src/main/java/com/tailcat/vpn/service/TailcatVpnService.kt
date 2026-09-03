package com.tailcat.vpn.service

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.tailcat.vpn.TailcatApplication
import com.tailcat.vpn.core.model.GatewayProfile
import com.tailcat.vpn.core.model.TunnelState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
            shutdown()
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

        if (startJob?.isActive == true || vpnInterface != null) return START_NOT_STICKY

        shuttingDown = false
        app.tunnelController.setTunnelState(TunnelState.CONNECTING)
        startForeground(
            VpnNotificationManager.NOTIFICATION_ID,
            app.notificationManager.buildNotification(
                state = TunnelState.CONNECTING,
                profileName = profile.name,
                metrics = app.tunnelController.networkMetrics.value
            )
        )

        startJob = serviceScope.launch { establishAndStartEngine(profile) }
        return START_NOT_STICKY
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

            val warmup = vpnBuilder(profile, dnsValidation.ip, fullRoutes = false).establish()
                ?: throw IllegalStateException("Android could not establish the VPN interface")
            vpnInterface = warmup
            currentCoroutineContext().ensureActive()
            app.tunnelEngine.attachTun(warmup.fd)
            app.tunnelEngine.updateNetworkState(networkState)
            check(
                EngineHealth.shouldConnect(
                    app.tunnelEngine.getStats(),
                    TunnelController.unixNow()
                )
            ) {
                "VPN engine did not become live after attach"
            }

            val routed = vpnBuilder(profile, dnsValidation.ip, fullRoutes = true).establish()
                ?: throw IllegalStateException("Android could not install default routes")
            val previous = vpnInterface
            vpnInterface = routed
            app.tunnelEngine.attachTun(routed.fd)
            runCatching { previous?.close() }
            currentCoroutineContext().ensureActive()
            app.tunnelEngine.updateNetworkState(networkState)
            val metrics = app.tunnelEngine.getStats()
            check(EngineHealth.shouldConnect(metrics, TunnelController.unixNow())) {
                "VPN engine did not become live after route attach"
            }
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

    private fun vpnBuilder(
        profile: GatewayProfile,
        dnsIp: String,
        fullRoutes: Boolean
    ): Builder {
        val builder = Builder()
            .setSession("OpenTailcat - ${profile.name}")
            .setMtu(profile.mtu)
            .addAddress("100.64.0.2", 32)
            .addAddress("fd7a:115c:a1e0::2", 128)
            .addDnsServer(dnsIp)
            .setBlocking(false)
        if (fullRoutes) {
            builder.addRoute("0.0.0.0", 0)
            builder.addRoute("::", 0)
        } else {
            builder.addRoute("100.64.0.2", 32)
            builder.addRoute("fd7a:115c:a1e0::2", 128)
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

    private fun shutdown() {
        if (shuttingDown) return
        shuttingDown = true
        startJob?.cancel()
        metricsCollectorJob?.cancel()

        runCatching { TailcatApplication.instance.tunnelEngine.stop() }
        runCatching { vpnInterface?.close() }
        vpnInterface = null

        TailcatApplication.instance.tunnelController.onVpnStopped()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        shutdown()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onRevoke() {
        shutdown()
        super.onRevoke()
    }

    companion object {
        const val ACTION_START_VPN = "com.tailcat.vpn.ACTION_START"
        const val ACTION_STOP_VPN = "com.tailcat.vpn.ACTION_STOP"
    }
}
