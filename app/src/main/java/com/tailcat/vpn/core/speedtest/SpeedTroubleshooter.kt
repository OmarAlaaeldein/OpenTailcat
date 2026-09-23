package com.tailcat.vpn.core.speedtest

import com.tailcat.vpn.core.model.NetworkMetrics
import com.tailcat.vpn.core.model.TunnelState
import com.tailcat.vpn.core.model.TransportType

enum class FindingSeverity { INFO, WARNING, ERROR }

data class TroubleshootFinding(
    val severity: FindingSeverity,
    val message: String
)

/**
 * Surfaces silent speed-test and tunnel failure causes as user-facing findings.
 *
 * Diagnostic text never includes public exit/Direct endpoints — those stay in
 * structured fields. Only private/loopback host hints may appear in free text.
 */
object SpeedTroubleshooter {

    private val PUBLIC_IP = Regex(
        """\b(?:\d{1,3}\.){3}\d{1,3}\b|\b(?:[0-9a-fA-F]{0,4}:){2,7}[0-9a-fA-F]{0,4}\b"""
    )
    private val PRIVATE_HINT = Regex(
        """\b(?:10\.\d{1,3}\.\d{1,3}\.\d{1,3}|192\.168\.\d{1,3}\.\d{1,3}|127\.\d{1,3}\.\d{1,3}\.\d{1,3}|172\.(?:1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3})\b"""
    )

    fun sanitizeMessage(raw: String?): String {
        if (raw.isNullOrBlank()) return raw.orEmpty()
        return PUBLIC_IP.replace(raw) { match ->
            if (PRIVATE_HINT.matches(match.value)) match.value else "[ip-redacted]"
        }
    }

    fun diagnose(
        tunnelState: TunnelState,
        metrics: NetworkMetrics?,
        stage: SpeedTestStage,
        errorMessage: String?,
        viaGateway: Boolean,
        nowUnixSec: Long,
        stageDetail: String? = null
    ): List<TroubleshootFinding> {
        val findings = mutableListOf<TroubleshootFinding>()
        val msg = sanitizeMessage(errorMessage)
        val detail = sanitizeMessage(stageDetail)

        if (stage == SpeedTestStage.FAILED) {
            if (msg.isNotBlank()) {
                findings += TroubleshootFinding(FindingSeverity.ERROR, msg)
            } else {
                findings += TroubleshootFinding(FindingSeverity.ERROR, "The benchmark could not complete")
            }
            if (detail.isNotBlank()) {
                findings += TroubleshootFinding(FindingSeverity.INFO, detail)
            }
        }

        if (viaGateway || tunnelState == TunnelState.CONNECTED) {
            when {
                metrics == null -> findings += TroubleshootFinding(
                    FindingSeverity.WARNING,
                    "No live tunnel telemetry — cannot attribute a slow or failed gateway benchmark"
                )
                !metrics.isLiveRunning(nowUnixSec) -> findings += TroubleshootFinding(
                    FindingSeverity.ERROR,
                    "Tunnel health is not live (state=${metrics.state.ifBlank { "unknown" }}) — gateway speed tests will fail"
                )
                else -> {
                    if (metrics.discoStale) {
                        findings += TroubleshootFinding(
                            FindingSeverity.WARNING,
                            "Discovery RTT is stale — transport may be degraded (DERP fallback or no recent path probe)"
                        )
                    }
                    when (metrics.transportType) {
                        TransportType.DERP_RELAY -> findings += TroubleshootFinding(
                            FindingSeverity.WARNING,
                            "Relaying through DERP (region ${metrics.derpRegionCode ?: metrics.derpRegionId ?: "?"}) — expect higher latency than a direct path"
                        )
                        TransportType.UNKNOWN -> findings += TroubleshootFinding(
                            FindingSeverity.WARNING,
                            "Transport path is unknown — direct vs relay cannot be confirmed"
                        )
                        TransportType.DIRECT_P2P -> Unit
                    }
                    if (metrics.tcpOnly) {
                        findings += TroubleshootFinding(
                            FindingSeverity.WARNING,
                            "Gateway is TCP-only for this session — non-DNS UDP is dropped; DNS uses TCP"
                        )
                    }
                    if (metrics.rttLatencyMs > 0 && metrics.rttLatencyMs >= 250) {
                        findings += TroubleshootFinding(
                            FindingSeverity.WARNING,
                            "Tunnel RTT is ${metrics.rttLatencyMs} ms — slow path or long relay"
                        )
                    }
                    val drops = metrics.dropCounters
                    val dropTotal = drops.malformedIp + drops.mtuExceeded +
                        drops.queueExhaustion + drops.policyRejections
                    if (dropTotal > 0) {
                        findings += TroubleshootFinding(
                            FindingSeverity.WARNING,
                            "Data-plane drops: malformed=${drops.malformedIp}, mtu=${drops.mtuExceeded}, " +
                                "queue=${drops.queueExhaustion}, policy=${drops.policyRejections}"
                        )
                    }
                    if (metrics.egressAuditError != null) {
                        findings += TroubleshootFinding(
                            FindingSeverity.WARNING,
                            "Exit-audit probe failed: ${sanitizeMessage(metrics.egressAuditError)}"
                        )
                    }
                    if (!metrics.ipv6Egress && stage == SpeedTestStage.FAILED) {
                        findings += TroubleshootFinding(
                            FindingSeverity.INFO,
                            "Gateway IPv6 egress is off — IPv6 destinations fall back to tunneled IPv4"
                        )
                    }
                }
            }
        } else if (viaGateway) {
            findings += TroubleshootFinding(
                FindingSeverity.WARNING,
                "Gateway benchmark requested while not CONNECTED — native measures require a running tunnel"
            )
        }

        if (stage == SpeedTestStage.FAILED && viaGateway) {
            findings += TroubleshootFinding(
                FindingSeverity.INFO,
                "Re-test on the physical path (disconnect first) to compare gateway vs device network"
            )
        }

        return findings
    }
}
