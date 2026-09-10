package com.tailcat.vpn.core.metrics

import com.tailcat.vpn.core.model.NetworkMetrics
import com.tailcat.vpn.core.model.TransportType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficFormatTest {

    @Test
    fun formatBytesScales() {
        assertEquals("0 B", TrafficFormat.formatBytes(0))
        assertEquals("512 B", TrafficFormat.formatBytes(512))
        assertEquals("1.5 KB", TrafficFormat.formatBytes(1536))
        assertEquals("2.0 MB", TrafficFormat.formatBytes(2L * 1024 * 1024))
    }

    @Test
    fun formatRateFromKbpsUsesByteUnits() {
        // 8 kbps = 1000 B/s
        assertEquals("1000 B/s", TrafficFormat.formatRateFromKbps(8))
        // 0 stays explicit B/s (the bug symptom users reported)
        assertEquals("0 B/s", TrafficFormat.formatRateFromKbps(0))
        // 8192 kbps = 1_024_000 B/s ~= 1000.0 KB/s
        assertEquals("1000.0 KB/s", TrafficFormat.formatRateFromKbps(8192))
    }

    @Test
    fun connectedNotificationUsesLiveRatesNotWireGuardZeros() {
        val metrics = NetworkMetrics(
            transportType = TransportType.DERP_RELAY,
            derpRegionId = 302,
            rttLatencyMs = 18,
            // WG peer counters unavailable — always 0 in production
            txBytes = 0,
            rxBytes = 0,
            wireguardTxBytes = 0,
            wireguardRxBytes = 0,
            // TUN accounting + rateCalcLoop are authoritative
            tunTxBytes = 480_000,
            tunRxBytes = 870_000,
            txRateKbps = 120, // 15_000 B/s => 14.6 KB/s
            rxRateKbps = 800, // 100_000 B/s => 97.7 KB/s
        )
        val text = TrafficFormat.connectedNotificationContent(metrics)
        assertTrue(text.contains("DERP Relay #302"))
        assertTrue(text.contains("(18ms)"))
        assertTrue("expected live download rate, got: $text", text.contains("⬇ 97.7 KB/s"))
        assertTrue("expected live upload rate, got: $text", text.contains("⬆ 14.6 KB/s"))
        assertFalse("must not show bare WG zero totals as speed", text.contains("⬇ 0 B"))
        assertFalse(text.contains("⬆ 0 B"))
    }

    @Test
    fun connectedNotificationZeroRatesWhenIdle() {
        val metrics = NetworkMetrics(
            transportType = TransportType.DIRECT_P2P,
            txRateKbps = 0,
            rxRateKbps = 0,
        )
        val text = TrafficFormat.connectedNotificationContent(metrics)
        assertEquals("Direct P2P | ⬇ 0 B/s  ⬆ 0 B/s", text)
    }
}
