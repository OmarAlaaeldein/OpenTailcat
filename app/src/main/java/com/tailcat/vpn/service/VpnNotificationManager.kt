package com.tailcat.vpn.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.tailcat.vpn.R
import com.tailcat.vpn.core.model.NetworkMetrics
import com.tailcat.vpn.core.model.TransportType
import com.tailcat.vpn.core.model.TunnelState
import com.tailcat.vpn.ui.MainActivity

class VpnNotificationManager(private val context: Context) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.vpn_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.vpn_channel_desc)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun buildNotification(
        state: TunnelState,
        profileName: String,
        metrics: NetworkMetrics
    ): Notification {
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            context,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(context, TailcatVpnService::class.java).apply {
            action = TailcatVpnService.ACTION_STOP_VPN
        }
        val stopPendingIntent = PendingIntent.getService(
            context,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val title = when (state) {
            TunnelState.CONNECTING -> "Tailcat: Connecting to $profileName..."
            TunnelState.CONNECTED -> "Tailcat: Protected ($profileName)"
            TunnelState.RECONNECTING -> "Tailcat: Reconnecting..."
            TunnelState.DEGRADED -> "Tailcat: Tunnel Degraded"
            TunnelState.DISCONNECTED -> "Tailcat: Disconnected"
        }

        val transportStr = when (metrics.transportType) {
            TransportType.DIRECT_P2P -> "Direct P2P (${metrics.rttLatencyMs}ms)"
            TransportType.DERP_RELAY -> "DERP Relay #${metrics.derpRegionId ?: "?"} (${metrics.rttLatencyMs}ms)"
            TransportType.UNKNOWN -> "Establishing transport..."
        }

        val content = if (state == TunnelState.CONNECTED) {
            "$transportStr | ⬇ ${formatBytes(metrics.rxBytes)}  ⬆ ${formatBytes(metrics.txBytes)}"
        } else {
            "Tap to manage your VPN connection"
        }

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(openPendingIntent)
            .setOngoing(state == TunnelState.CONNECTED || state == TunnelState.CONNECTING)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f MB".format(mb)
        val gb = mb / 1024.0
        return "%.2f GB".format(gb)
    }

    companion object {
        const val CHANNEL_ID = "tailcat_vpn_channel"
        const val NOTIFICATION_ID = 1001
    }
}
