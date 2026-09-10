package com.tailcat.vpn.core.metrics

import com.tailcat.vpn.core.model.NetworkMetrics
import com.tailcat.vpn.core.model.TransportType
import java.util.Locale

/**
 * Human-readable TUN traffic totals and live rates for notification / telemetry UI.
 *
 * Native [NetworkMetrics.txBytes]/[NetworkMetrics.rxBytes] are WireGuard peer
 * counters and stay 0 without a Client.Status API. Live rates
 * ([NetworkMetrics.txRateKbps]/[NetworkMetrics.rxRateKbps]) and TUN byte totals
 * ([NetworkMetrics.tunTxBytes]/[NetworkMetrics.tunRxBytes]) come from pump
 * accounting — UI must use those fields, never WG zeros.
 */
object TrafficFormat {
    /** Format a cumulative byte count (TUN totals). */
    fun formatBytes(bytes: Long): String {
        val value = bytes.coerceAtLeast(0L)
        if (value < 1024) return "$value B"
        val kb = value / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.2f GB", gb)
    }

    /**
     * Format a live rate from native kilobits/sec into byte/s units
     * (B/s, KB/s, MB/s) for the status notification.
     */
    fun formatRateFromKbps(kbps: Long): String {
        val bytesPerSec = (kbps.coerceAtLeast(0L) * 1000L) / 8L
        if (bytesPerSec < 1024) return "$bytesPerSec B/s"
        val kb = bytesPerSec / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB/s", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB/s", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.2f GB/s", gb)
    }

    /** Compact Kb/s label matching TelemetryCard (kilobits/sec). */
    fun formatKbps(kbps: Long): String = "${kbps.coerceAtLeast(0L)} Kb/s"

    /**
     * Foreground-notification content while CONNECTED: transport + live
     * download/upload rates from TUN pump accounting.
     */
    fun connectedNotificationContent(metrics: NetworkMetrics): String {
        val rttSuffix = if (metrics.rttLatencyMs > 0) " (${metrics.rttLatencyMs}ms)" else ""
        val transportStr = when (metrics.transportType) {
            TransportType.DIRECT_P2P -> "Direct P2P$rttSuffix"
            TransportType.DERP_RELAY -> "DERP Relay #${metrics.derpRegionId ?: "?"}$rttSuffix"
            TransportType.UNKNOWN -> "Establishing transport..."
        }
        val down = formatRateFromKbps(metrics.rxRateKbps)
        val up = formatRateFromKbps(metrics.txRateKbps)
        return "$transportStr | ⬇ $down  ⬆ $up"
    }
}
