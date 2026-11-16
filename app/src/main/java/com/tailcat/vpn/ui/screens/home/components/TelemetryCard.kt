package com.tailcat.vpn.ui.screens.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tailcat.vpn.core.metrics.TrafficFormat
import com.tailcat.vpn.core.model.EgressInfo
import com.tailcat.vpn.core.model.NetworkMetrics
import com.tailcat.vpn.service.VpnInterfaceSpec
import com.tailcat.vpn.ui.theme.AccentCyan
import com.tailcat.vpn.ui.theme.EmeraldConnected
import com.tailcat.vpn.ui.theme.SurfaceElevated
import com.tailcat.vpn.ui.theme.TailcatShapes
import com.tailcat.vpn.ui.theme.TailcatText
import com.tailcat.vpn.ui.theme.TextMuted
import com.tailcat.vpn.ui.theme.TextPrimary
import com.tailcat.vpn.ui.theme.TextSecondary
import com.tailcat.vpn.ui.theme.VioletDerp
import com.tailcat.vpn.ui.theme.YellowWarning
import com.tailcat.vpn.ui.theme.panel

@Composable
fun TelemetryCard(
    metrics: NetworkMetrics,
    egressInfo: EgressInfo,
    mtu: Int,
    onRefreshIp: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Use authoritative liveness (RUNNING + fresh health) not just transport presence.
    // A stale transport (DIRECT_P2P) with old health would previously keep showing
    // the last tunnelEgressIp (which could be a 200.x exit) even after the data
    // plane failed and metrics were stale. Now we require live RUNNING.
    val nowSec = System.currentTimeMillis() / 1000L
    val tunnelActive = metrics.isLiveRunning(nowSec)
    val egress = TelemetryDisplay.egress(metrics, egressInfo.ip, nowSec)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .panel()
            .padding(14.dp)
    ) {
        // IpAuditor follows whatever route the app's own sockets take, so
        // connected-mode egress comes only from the native engine's
        // through-WireGuard probe.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .panel(TailcatShapes.Tile, fill = SurfaceElevated)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Default.Public,
                    contentDescription = "Public IP",
                    tint = AccentCyan,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "${egress.label}: ${egress.value}",
                        color = TextPrimary,
                        style = TailcatText.Metric,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (tunnelActive) {
                        Text(
                            text = "VPN address: ${VpnInterfaceSpec.IPV4_ADDRESS} / ${VpnInterfaceSpec.IPV6_ADDRESS}",
                            color = TextSecondary,
                            style = TailcatText.Eyebrow
                        )
                    } else if (egressInfo.city != null || egressInfo.country != null) {
                        val location = listOfNotNull(egressInfo.city, egressInfo.country).joinToString(", ")
                        Text(text = location, color = TextSecondary, style = TailcatText.Eyebrow)
                    }
                }
            }

            if (!tunnelActive && egressInfo.isChecking) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = AccentCyan,
                    modifier = Modifier.size(16.dp)
                )
            } else if (!tunnelActive) {
                IconButton(onClick = onRefreshIp, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh IP",
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Transport header (Direct P2P vs DERP)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            val transport = TelemetryDisplay.transport(metrics)
            val badgeColor = when (transport.path) {
                TelemetryDisplay.Path.DIRECT -> EmeraldConnected
                TelemetryDisplay.Path.RELAY -> VioletDerp
                TelemetryDisplay.Path.NOT_RESPONDING -> YellowWarning
                TelemetryDisplay.Path.NONE -> TextMuted
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f, fill = false)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(badgeColor, CircleShape)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = transport.text,
                    color = badgeColor,
                    style = TailcatText.Eyebrow,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // Measured UDP capability: when the engine latched TCP-only
                // (gateway UDP unreachable at handshake), non-DNS UDP is
                // dropped and DNS rides TCP. Speedtest is TCP-only and stays
                // green in this mode, so say so instead of looking healthy.
                if (tunnelActive && metrics.tcpOnly) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "TCP-only", color = TextSecondary, style = TailcatText.Eyebrow)
                }
                if (tunnelActive && !metrics.ipv6Egress) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "IPv4 egress", color = TextSecondary, style = TailcatText.Eyebrow)
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = TelemetryDisplay.rtt(metrics, nowSec),
                color = TextSecondary,
                style = TailcatText.Eyebrow,
                maxLines = 1
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Traffic and MTU tiles.
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            StatTile(
                icon = Icons.Default.ArrowDownward,
                iconDescription = "Download",
                tint = EmeraldConnected,
                primary = TrafficFormat.formatBytes(metrics.tunRxBytes),
                secondary = TrafficFormat.formatRate(metrics.rxRateKbps),
                modifier = Modifier.weight(1f)
            )
            StatTile(
                icon = Icons.Default.ArrowUpward,
                iconDescription = "Upload",
                tint = VioletDerp,
                primary = TrafficFormat.formatBytes(metrics.tunTxBytes),
                secondary = TrafficFormat.formatRate(metrics.txRateKbps),
                modifier = Modifier.weight(1f)
            )
            Column(
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .panel(TailcatShapes.Tile, fill = SurfaceElevated)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text(text = "MTU", color = TextMuted, style = TailcatText.Eyebrow)
                Text(text = "$mtu", color = TextPrimary, style = TailcatText.Metric)
            }
        }

        // Factual telemetry discriminators: only shown when the engine
        // reported a non-zero counter.
        if (metrics.dnsQueries > 0 || metrics.dropCounters.policyRejections > 0) {
            Spacer(modifier = Modifier.height(10.dp))
            if (metrics.dnsQueries > 0) {
                Text(
                    // UDP/53 queries plus TCP/53 connections forwarded to
                    // the gateway; encrypted DNS (Private DNS) is not counted.
                    text = "Plain DNS queries: ${metrics.dnsQueries}",
                    color = TextSecondary,
                    style = TailcatText.Eyebrow
                )
            }
            if (metrics.dropCounters.policyRejections > 0) {
                Text(
                    text = "Policy rejections: ${metrics.dropCounters.policyRejections}",
                    color = TextSecondary,
                    style = TailcatText.Eyebrow
                )
            }
        }
    }
}

@Composable
private fun StatTile(
    icon: ImageVector,
    iconDescription: String,
    tint: Color,
    primary: String,
    secondary: String,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .panel(TailcatShapes.Tile, fill = SurfaceElevated)
            .padding(horizontal = 10.dp, vertical = 10.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = iconDescription,
            tint = tint,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Column {
            Text(text = primary, color = TextPrimary, style = TailcatText.Metric, maxLines = 1)
            Text(text = secondary, color = TextMuted, style = TailcatText.Eyebrow, maxLines = 1)
        }
    }
}
