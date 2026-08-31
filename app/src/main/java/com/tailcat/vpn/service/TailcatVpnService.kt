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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class TailcatVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var metricsCollectorJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (action == ACTION_STOP_VPN) {
            stopVpn()
            return START_NOT_STICKY
        }

        val app = TailcatApplication.instance
        val activeProfile = app.profileRepository.activeProfile.value

        if (activeProfile == null) {
            stopVpn()
            return START_NOT_STICKY
        }

        startVpn(activeProfile)
        return START_STICKY
    }

    private fun startVpn(profile: GatewayProfile) {
        try {
            val app = TailcatApplication.instance
            app.tunnelController.setTunnelState(TunnelState.CONNECTING)

            val initialNotification = app.notificationManager.buildNotification(
                state = TunnelState.CONNECTING,
                profileName = profile.name,
                metrics = app.tunnelController.networkMetrics.value
            )
            startForeground(VpnNotificationManager.NOTIFICATION_ID, initialNotification)

            val builder = Builder()
                .setSession("Tailcat - ${profile.name}")
                .setMtu(profile.mtu)
                .addAddress("100.64.0.2", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer(profile.customDns)
                .setBlocking(false)

            // IPv6 Route
            try {
                builder.addAddress("fd7a:115c:a1e0::2", 128)
                builder.addRoute("::", 0)
            } catch (e: Exception) {
                // Ignore if IPv6 unsupported
            }

            // Split Tunneling (Excluded Apps)
            val excludedPackages = app.preferencesStore.splitTunnelExcludedApps
            for (pkg in excludedPackages) {
                try {
                    builder.addDisallowedApplication(pkg)
                } catch (e: Exception) {
                    // Ignore missing packages
                }
            }

            // Disallow our own app from being tunneled to avoid loopback
            builder.addDisallowedApplication(packageName)

            vpnInterface = builder.establish()

            if (vpnInterface == null) {
                app.tunnelController.setTunnelState(TunnelState.DISCONNECTED)
                stopSelf()
                return
            }

            val fd = vpnInterface!!.fd
            app.tunnelController.onVpnInterfaceEstablished(fd, profile)

            startMetricsNotificationUpdater(profile)

        } catch (e: Exception) {
            e.printStackTrace()
            stopVpn()
        }
    }

    private fun startMetricsNotificationUpdater(profile: GatewayProfile) {
        val app = TailcatApplication.instance
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        metricsCollectorJob?.cancel()
        metricsCollectorJob = serviceScope.launch {
            app.tunnelController.networkMetrics.collectLatest { metrics ->
                val state = app.tunnelController.tunnelState.value
                val notification = app.notificationManager.buildNotification(
                    state = state,
                    profileName = profile.name,
                    metrics = metrics
                )
                notificationManager.notify(VpnNotificationManager.NOTIFICATION_ID, notification)
            }
        }
    }

    private fun stopVpn() {
        metricsCollectorJob?.cancel()
        val app = TailcatApplication.instance
        app.tunnelController.onVpnStopped()

        try {
            vpnInterface?.close()
            vpnInterface = null
        } catch (e: Exception) {
            e.printStackTrace()
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    companion object {
        const val ACTION_START_VPN = "com.tailcat.vpn.ACTION_START"
        const val ACTION_STOP_VPN = "com.tailcat.vpn.ACTION_STOP"
    }
}
