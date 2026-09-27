package com.tailcat.vpn.ui.screens.home.components

import com.tailcat.vpn.core.model.NetworkMetrics
import com.tailcat.vpn.core.model.TransportType
import com.tailcat.vpn.core.model.TunnelState

/**
 * Text the Home screen shows for the tunnel, kept free of Compose so it can
 * be unit tested. Nothing here may present a stale or missing measurement
 * as live (invariant 4).
 */
object TelemetryDisplay {

    fun statusLabel(state: TunnelState, deviceOffline: Boolean, engineAvailable: Boolean): String = when {
        deviceOffline && state == TunnelState.DISCONNECTED -> "OFFLINE • NO INTERNET"
        !engineAvailable && state == TunnelState.DISCONNECTED -> "ENGINE REQUIRED • VPN DISABLED"
        state == TunnelState.CONNECTED -> "CONNECTED"
        state == TunnelState.CONNECTING -> "ESTABLISHING TUNNEL..."
        state == TunnelState.RECONNECTING -> "RECONNECTING..."
        // discoStale means the gateway stopped answering, not that traffic is relayed.
        state == TunnelState.DEGRADED -> "DEGRADED • GATEWAY NOT RESPONDING"
        else -> "TAP TO CONNECT"
    }

    data class Egress(val label: String, val value: String)

    /**
     * While the tunnel is live the exit address comes only from the engine's
     * audit through the tunnel; otherwise the device's own address is shown.
     */
    fun egress(metrics: NetworkMetrics, deviceIp: String, nowSec: Long): Egress {
        if (!metrics.isLiveRunning(nowSec)) return Egress("Device IP", deviceIp)
        val exit = metrics.tunnelEgressIp
        val error = metrics.egressAuditError
        val value = when {
            exit != null -> exit
            !error.isNullOrBlank() -> "unavailable (${shorten(error)})"
            else -> "Checking…"
        }
        return Egress("Exit IP", value)
    }

    enum class Path { DIRECT, RELAY, NOT_RESPONDING, NONE }

    data class Transport(val path: Path, val text: String)

    fun transport(metrics: NetworkMetrics): Transport = when {
        metrics.transportType == TransportType.UNKNOWN -> Transport(Path.NONE, "DISCONNECTED")
        // The engine keeps the last transport after DiscoPing fails; do not show it as current.
        metrics.discoStale -> Transport(Path.NOT_RESPONDING, "GATEWAY NOT RESPONDING")
        metrics.transportType == TransportType.DIRECT_P2P -> Transport(Path.DIRECT, "DIRECT P2P")
        else -> {
            val name = metrics.derpRegionName
                ?: metrics.derpRegionCode
                ?: metrics.derpRegionId?.let { "region $it" }
            Transport(Path.RELAY, if (name != null) "DERP RELAY ($name)" else "DERP RELAY")
        }
    }

    /** RTT text; a stale sample is replaced by how long the gateway has been silent. */
    fun rtt(metrics: NetworkMetrics, nowSec: Long): String = when {
        metrics.transportType == TransportType.UNKNOWN -> "—"
        metrics.discoStale -> {
            val silent = nowSec - metrics.lastDiscoOkUnixSec
            if (metrics.lastDiscoOkUnixSec > 0 && silent > 0) "no reply for ${silent}s" else "no reply"
        }
        metrics.rttLatencyMs <= 0 && metrics.jitterMs == null -> "—"
        metrics.jitterMs != null -> "${metrics.rttLatencyMs} ms (±${metrics.jitterMs})"
        else -> "${metrics.rttLatencyMs} ms"
    }

    private fun shorten(message: String): String {
        val oneLine = message.lineSequence().first().trim()
        return if (oneLine.length <= MAX_CAUSE) oneLine else oneLine.take(MAX_CAUSE - 1) + "…"
    }

    private const val MAX_CAUSE = 60
}
