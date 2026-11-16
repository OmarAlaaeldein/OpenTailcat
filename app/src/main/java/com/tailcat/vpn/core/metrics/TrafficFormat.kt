package com.tailcat.vpn.core.metrics

import com.tailcat.vpn.core.model.NetworkMetrics
import com.tailcat.vpn.core.model.TransportType
import java.util.Locale

/**
 * Human-readable TUN traffic totals and live rates for the notification and
 * the telemetry card.
 *
 * What the numbers mean: [NetworkMetrics.tunTxBytes]/[NetworkMetrics.tunRxBytes]
 * are the bytes that crossed the TUN (upload = read from it, download = written
 * to it), IP headers included, the same quantity the kernel reports for the
 * interface. [NetworkMetrics.txRateKbps]/[NetworkMetrics.rxRateKbps] are those
 * bytes per second as kilobits (1 kb = 1000 bits). The WireGuard counters
 * [NetworkMetrics.txBytes]/[NetworkMetrics.rxBytes] stay 0 without a
 * Client.Status API, so the UI never uses them.
 *
 * Units are decimal throughout so a rate and a total always agree:
 * 100 Mb/s for 10 s is exactly 125.0 MB. Totals use B/kB/MB/GB, rates use
 * kb/s, Mb/s, Gb/s (the same scale as the speed test's Mbps).
 */
object TrafficFormat {
    private const val BASE = 1000.0

    /**
     * Largest value in a unit that still prints below the next unit at one
     * decimal: 999.95 prints as "1000.0", so it must roll over instead.
     */
    private const val ROLLOVER = 999.95

    /** Format a cumulative byte count (TUN totals). */
    fun formatBytes(bytes: Long): String {
        val value = bytes.coerceAtLeast(0L)
        if (value < 1000) return "$value B"
        var v = value / BASE
        for (unit in arrayOf("kB", "MB")) {
            if (v < ROLLOVER) return String.format(Locale.US, "%.1f %s", v, unit)
            v /= BASE
        }
        return String.format(Locale.US, "%.2f GB", v)
    }

    /** Format a live rate given in kilobits per second. */
    fun formatRate(kbps: Long): String {
        val value = kbps.coerceAtLeast(0L)
        if (value < 1000) return "$value kb/s"
        val mbps = value / BASE
        if (mbps < ROLLOVER) return String.format(Locale.US, "%.1f Mb/s", mbps)
        return String.format(Locale.US, "%.2f Gb/s", mbps / BASE)
    }

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
        val down = formatRate(metrics.rxRateKbps)
        val up = formatRate(metrics.txRateKbps)
        return "$transportStr | ⬇ $down  ⬆ $up"
    }
}
