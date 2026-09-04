package com.tailcat.vpn.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.tailcat.vpn.R
import com.tailcat.vpn.core.model.NetworkMetrics
import com.tailcat.vpn.core.model.TransportType
import com.tailcat.vpn.core.model.TunnelState
import com.tailcat.vpn.ui.MainActivity
import java.util.Locale

class VpnNotificationManager(private val context: Context) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun createNotificationChannels() {
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
            TunnelState.CONNECTING -> "OpenTailcat: Connecting to $profileName..."
            TunnelState.CONNECTED -> "OpenTailcat: Connected ($profileName)"
            TunnelState.RECONNECTING -> "OpenTailcat: Reconnecting..."
            TunnelState.DEGRADED -> "OpenTailcat: Tunnel Degraded"
            TunnelState.DISCONNECTED -> "OpenTailcat: Disconnected"
        }

        val rttSuffix = if (metrics.rttLatencyMs > 0) " (${metrics.rttLatencyMs}ms)" else ""
        val transportStr = when (metrics.transportType) {
            TransportType.DIRECT_P2P -> "Direct P2P$rttSuffix"
            TransportType.DERP_RELAY -> "DERP Relay #${metrics.derpRegionId ?: "?"}$rttSuffix"
            TransportType.UNKNOWN -> "Establishing transport..."
        }

        val content = if (state == TunnelState.CONNECTED) {
            "$transportStr | ⬇ ${formatBytes(metrics.rxBytes)}  ⬆ ${formatBytes(metrics.txBytes)}"
        } else {
            "Tap to manage your VPN connection"
        }

        val isActive = state != TunnelState.DISCONNECTED
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn_status)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(openPendingIntent)
            .setOngoing(isActive)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (isActive) {
            builder.addAction(R.drawable.ic_vpn_status, "Disconnect", stopPendingIntent)
        }
        return builder.build()
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.getDefault(), "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.getDefault(), "%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.getDefault(), "%.2f GB", gb)
    }

    companion object {
        const val CHANNEL_ID = "tailcat_vpn_channel"
        const val NOTIFICATION_ID = 1001
    }
}
