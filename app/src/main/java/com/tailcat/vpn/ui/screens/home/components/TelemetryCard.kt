package com.tailcat.vpn.ui.screens.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tailcat.vpn.core.metrics.TrafficFormat
import com.tailcat.vpn.core.model.EgressInfo
import com.tailcat.vpn.core.model.NetworkMetrics
import com.tailcat.vpn.core.model.TransportType
import com.tailcat.vpn.ui.theme.AccentCyan
import com.tailcat.vpn.ui.theme.BorderSubtle
import com.tailcat.vpn.ui.theme.EmeraldConnected
import com.tailcat.vpn.ui.theme.SurfaceDark
import com.tailcat.vpn.ui.theme.SurfaceElevated
import com.tailcat.vpn.ui.theme.TextMuted
import com.tailcat.vpn.ui.theme.TextPrimary
import com.tailcat.vpn.ui.theme.TextSecondary
import com.tailcat.vpn.ui.theme.VioletDerp

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
    val displayedIp = if (tunnelActive) metrics.tunnelEgressIp ?: "Checking…" else egressInfo.ip
    val ipLabel = if (tunnelActive) "Exit IP" else "Device IP"

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceDark)
            .border(1.dp, BorderSubtle, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Column {
            // The app UID bypasses the Android VPN. Connected-mode egress must therefore
            // come from the native engine's through-WireGuard probe, never IpAuditor.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(SurfaceElevated)
                    .padding(horizontal = 10.dp, vertical = 7.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Public,
                        contentDescription = "Public IP",
                        tint = AccentCyan,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "$ipLabel: $displayedIp",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = TextPrimary,
                                    fontSize = 14.sp
                                )
                            )
                        }
                        if (tunnelActive) {
                            Text(
                                text = "VPN address: 100.64.0.2 / fd7a:115c:a1e0::2",
                                style = MaterialTheme.typography.labelMedium.copy(color = TextSecondary)
                            )
                        } else if (egressInfo.city != null || egressInfo.country != null) {
                            val location = listOfNotNull(egressInfo.city, egressInfo.country).joinToString(", ")
                            Text(
                                text = location,
                                style = MaterialTheme.typography.labelMedium.copy(color = TextSecondary)
                            )
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
                    IconButton(
                        onClick = onRefreshIp,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh IP",
                            tint = TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Transport header (Direct P2P vs DERP)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val (badgeColor, icon, label) = when (metrics.transportType) {
                        TransportType.DIRECT_P2P -> {
                            val ep = metrics.directEndpoint
                            val text = if (!ep.isNullOrBlank()) "DIRECT P2P ($ep)" else "DIRECT P2P"
                            Triple(EmeraldConnected, Icons.Default.FlashOn, text)
                        }
                        TransportType.DERP_RELAY -> {
                            val name = metrics.derpRegionName
                                ?: metrics.derpRegionCode
                                ?: metrics.derpRegionId?.let { "DERP $it" }
                                ?: "DERP"
                            Triple(
                                VioletDerp,
                                Icons.Default.Shield,
                                "DERP RELAY ($name)"
                            )
                        }
                        TransportType.UNKNOWN -> Triple(TextMuted, Icons.Default.Shield, "DISCONNECTED")
                    }

                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(badgeColor)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = badgeColor,
                            fontSize = 13.sp
                        )
                    )
                    // Measured UDP capability: when the engine latched TCP-only
                    // (gateway UDP unreachable at handshake), non-DNS UDP is
                    // dropped and DNS rides TCP. Speedtest is TCP-only and stays
                    // green in this mode, so say so instead of looking healthy.
                    if (tunnelActive && metrics.tcpOnly) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "TCP-only",
                            style = MaterialTheme.typography.labelMedium.copy(
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        )
                    }
                    if (tunnelActive && !metrics.ipv6Egress) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "IPv4 egress",
                            style = MaterialTheme.typography.labelMedium.copy(
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        )
                    }
                }

                Text(
                    text = if (metrics.transportType == TransportType.UNKNOWN) {
                        "—"
                    } else if (metrics.rttLatencyMs <= 0 && metrics.jitterMs == null) {
                        "—"
                    } else if (metrics.jitterMs != null) {
                        "${metrics.rttLatencyMs} ms (±${metrics.jitterMs})"
                    } else {
                        "${metrics.rttLatencyMs} ms"
                    },
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Metrics grid (Download / Upload / MTU)
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Downloader
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.ArrowDownward,
                        contentDescription = "Download",
                        tint = EmeraldConnected,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Column {
                        Text(
                            text = TrafficFormat.formatBytes(metrics.tunRxBytes),
                            style = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary)
                        )
                        Text(
                            text = TrafficFormat.formatKbps(metrics.rxRateKbps),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }

                // Uploader
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.ArrowUpward,
                        contentDescription = "Upload",
                        tint = VioletDerp,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Column {
                        Text(
                            text = TrafficFormat.formatBytes(metrics.tunTxBytes),
                            style = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary)
                        )
                        Text(
                            text = TrafficFormat.formatKbps(metrics.txRateKbps),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }

                // Invariants Badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(SurfaceElevated)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "MTU $mtu",
                        style = MaterialTheme.typography.labelMedium.copy(color = TextSecondary)
                    )
                }
            }

            // Factual telemetry discriminators: only shown when the engine
            // reported a non-zero counter or a stale RTT sample.
            if (metrics.dnsQueries > 0 || metrics.dropCounters.policyRejections > 0 || metrics.discoStale) {
                Spacer(modifier = Modifier.height(8.dp))
                Column {
                    if (metrics.dnsQueries > 0) {
                        Text(
                            text = "DNS queries: ${metrics.dnsQueries}",
                            style = MaterialTheme.typography.labelMedium.copy(color = TextSecondary)
                        )
                    }
                    if (metrics.dropCounters.policyRejections > 0) {
                        Text(
                            text = "Policy rejections: ${metrics.dropCounters.policyRejections}",
                            style = MaterialTheme.typography.labelMedium.copy(color = TextSecondary)
                        )
                    }
                    if (metrics.discoStale) {
                        Text(
                            text = "RTT stale (relay)",
                            style = MaterialTheme.typography.labelMedium.copy(color = TextSecondary)
                        )
                    }
                }
            }
        }
    }
}

