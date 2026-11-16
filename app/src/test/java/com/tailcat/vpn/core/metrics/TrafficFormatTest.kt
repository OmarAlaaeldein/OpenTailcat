package com.tailcat.vpn.core.metrics

import com.tailcat.vpn.core.model.NetworkMetrics
import com.tailcat.vpn.core.model.TransportType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficFormatTest {

    @Test
    fun formatBytesUsesDecimalUnits() {
        assertEquals("0 B", TrafficFormat.formatBytes(0))
        assertEquals("512 B", TrafficFormat.formatBytes(512))
        assertEquals("999 B", TrafficFormat.formatBytes(999))
        assertEquals("1.0 kB", TrafficFormat.formatBytes(1000))
        assertEquals("1.5 kB", TrafficFormat.formatBytes(1500))
        assertEquals("2.0 MB", TrafficFormat.formatBytes(2_000_000))
        assertEquals("1.23 GB", TrafficFormat.formatBytes(1_234_567_890))
        assertEquals("0 B", TrafficFormat.formatBytes(-1))
    }

    @Test
    fun formatBytesRollsOverInsteadOfPrintingAThousand() {
        // 1,048,575 B used to print as "1024.0 KB".
        assertEquals("1.0 MB", TrafficFormat.formatBytes(1_048_575))
        assertEquals("999.9 kB", TrafficFormat.formatBytes(999_949))
        assertEquals("1.0 MB", TrafficFormat.formatBytes(999_950))
        assertEquals("999.9 MB", TrafficFormat.formatBytes(999_949_999))
        assertEquals("1.00 GB", TrafficFormat.formatBytes(999_950_000))
    }

    @Test
    fun noValueNearAUnitBoundaryEverPrintsAThousand() {
        val bad = mutableListOf<String>()
        // The 1 kB, 1 MB and 1 GB boundaries. GB is the top unit, so a terabyte
        // legitimately prints as "1000.00 GB" and is not scanned.
        var unit = 1000L
        repeat(3) {
            for (delta in -2000L..2000L) {
                val text = TrafficFormat.formatBytes(unit + delta)
                if ("1000.0" in text || "1000 " in text || "1000.00" in text) bad += "$unit${if (delta >= 0) "+" else ""}$delta -> $text"
            }
            unit *= 1000
        }
        assertTrue("boundary values printed a bare thousand: $bad", bad.isEmpty())
    }

    @Test
    fun formatRateUsesKilobitsMegabitsGigabits() {
        assertEquals("0 kb/s", TrafficFormat.formatRate(0))
        assertEquals("999 kb/s", TrafficFormat.formatRate(999))
        assertEquals("1.0 Mb/s", TrafficFormat.formatRate(1000))
        assertEquals("100.0 Mb/s", TrafficFormat.formatRate(100_000))
        assertEquals("999.9 Mb/s", TrafficFormat.formatRate(999_949))
        assertEquals("1.00 Gb/s", TrafficFormat.formatRate(999_950))
        assertEquals("0 kb/s", TrafficFormat.formatRate(-5))
    }

    @Test
    fun aRateAndTheTotalItProducesAgree() {
        // 100 Mb/s = 12,500,000 B/s; over 10 s that is exactly 125.0 MB.
        val kbps = 100_000L
        val seconds = 10L
        val bytes = kbps * 1000 / 8 * seconds
        assertEquals(125_000_000L, bytes)
        assertEquals("100.0 Mb/s", TrafficFormat.formatRate(kbps))
        assertEquals("125.0 MB", TrafficFormat.formatBytes(bytes))
        // 8 Mb/s for 1 s is exactly 1.0 MB.
        assertEquals("1.0 MB", TrafficFormat.formatBytes(8_000L * 1000 / 8))
    }

    @Test
    fun connectedNotificationUsesLiveRatesNotWireGuardZeros() {
        val metrics = NetworkMetrics(
            transportType = TransportType.DERP_RELAY,
            derpRegionId = 302,
            rttLatencyMs = 18,
            // WG peer counters unavailable: always 0 in production
            txBytes = 0,
            rxBytes = 0,
            // TUN accounting + rateCalcLoop are authoritative
            tunTxBytes = 480_000,
            tunRxBytes = 870_000,
            txRateKbps = 120, // 15,000 B/s
            rxRateKbps = 8_000, // 1,000,000 B/s
        )
        val text = TrafficFormat.connectedNotificationContent(metrics)
        assertTrue(text.contains("DERP Relay #302"))
        assertTrue(text.contains("(18ms)"))
        assertTrue("expected live download rate, got: $text", text.contains("⬇ 8.0 Mb/s"))
        assertTrue("expected live upload rate, got: $text", text.contains("⬆ 120 kb/s"))
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
        assertEquals("Direct P2P | ⬇ 0 kb/s  ⬆ 0 kb/s", text)
    }
}
