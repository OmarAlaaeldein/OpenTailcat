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

            val builder = Builder()
                .setSession("OpenTailcat - ${profile.name}")
                .setMtu(profile.mtu)
                .addAddress("100.64.0.2", 32)
                .addRoute("0.0.0.0", 0)
                .addAddress("fd7a:115c:a1e0::2", 128)
                .addRoute("::", 0)
                .addDnsServer(dnsValidation.ip)
                .setBlocking(false)

            for (packageName in app.preferencesStore.splitTunnelExcludedApps) {
                runCatching { builder.addDisallowedApplication(packageName) }
            }

            // Native transport sockets must bypass the TUN to avoid routing back into themselves.
            // This also means in-process diagnostics report the device's direct public IP.
            builder.addDisallowedApplication(packageName)

            val established = builder.establish()
                ?: throw IllegalStateException("Android could not establish the VPN interface")
            vpnInterface = established
            currentCoroutineContext().ensureActive()

            // The route becomes active just before this fast attachment call. Any attachment
            // error immediately tears it down.
            app.tunnelEngine.attachTun(established.fd)
            app.tunnelEngine.updateNetworkState(networkState)
            val metrics = app.tunnelEngine.getStats()
            check(EngineHealth.shouldConnect(metrics, TunnelController.unixNow())) {
                "VPN engine did not become live after attach"
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
