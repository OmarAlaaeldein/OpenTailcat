package com.tailcat.vpn.ui

import com.tailcat.vpn.core.model.NetworkMetrics
import com.tailcat.vpn.core.model.TransportType
import com.tailcat.vpn.core.model.TunnelState
import com.tailcat.vpn.ui.screens.home.components.TelemetryDisplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TelemetryDisplayTest {

    private fun live(
        transport: TransportType = TransportType.DERP_RELAY,
        discoStale: Boolean = false,
        lastDiscoOk: Long = 0L,
        rtt: Long = 23L,
        jitter: Long? = 2L,
        regionId: Int? = 1
    ) = NetworkMetrics(
        state = "RUNNING",
        healthUnixSec = 1_000L,
        transportType = transport,
        discoStale = discoStale,
        lastDiscoOkUnixSec = lastDiscoOk,
        rttLatencyMs = rtt,
        jitterMs = jitter,
        derpRegionId = regionId
    )

    @Test
    fun degradedIsNotDescribedAsRelaying() {
        val label = TelemetryDisplay.statusLabel(TunnelState.DEGRADED, deviceOffline = false, engineAvailable = true)
        assertEquals("DEGRADED • GATEWAY NOT RESPONDING", label)
        assertFalse(label.contains("RELAY"))
    }

    @Test
    fun reconnectingIsNotCalledRoaming() {
        assertEquals(
            "RECONNECTING...",
            TelemetryDisplay.statusLabel(TunnelState.RECONNECTING, deviceOffline = false, engineAvailable = true)
        )
    }

    @Test
    fun staleDiscoHidesLastTransportAndRtt() {
        val m = live(transport = TransportType.DIRECT_P2P, discoStale = true, lastDiscoOk = 955L)
        assertEquals(TelemetryDisplay.Path.NOT_RESPONDING, TelemetryDisplay.transport(m).path)
        assertEquals("no reply for 45s", TelemetryDisplay.rtt(m, 1_000L))
        assertEquals("no reply", TelemetryDisplay.rtt(live(discoStale = true), 1_000L))
    }

    @Test
    fun freshSessionShowsTransportAndRtt() {
        assertEquals(TelemetryDisplay.Transport(TelemetryDisplay.Path.RELAY, "DERP RELAY (region 1)"),
            TelemetryDisplay.transport(live()))
        assertEquals("DIRECT P2P", TelemetryDisplay.transport(live(transport = TransportType.DIRECT_P2P)).text)
        assertEquals("23 ms (±2)", TelemetryDisplay.rtt(live(), 1_000L))
        assertEquals("23 ms", TelemetryDisplay.rtt(live(jitter = null), 1_000L))
        assertEquals("—", TelemetryDisplay.rtt(live(transport = TransportType.UNKNOWN), 1_000L))
    }
}
