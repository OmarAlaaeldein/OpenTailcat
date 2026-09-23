package com.tailcat.vpn.core.speedtest

import com.tailcat.vpn.core.model.NetworkMetrics
import java.util.Locale

/**
 * Builds a plain-text troubleshooter report for clipboard export.
 *
 * Never includes public exit/direct endpoints or tokens. Free-text fields are
 * passed through [SpeedTroubleshooter.sanitizeMessage].
 */
object SpeedTestReport {

    fun build(
        result: SpeedTestResult,
        nowUnixSec: Long,
        appVersion: String
    ): String {
        val lines = mutableListOf<String>()
        lines += "OpenTailcat troubleshooter report"
        lines += "app=$appVersion generated=$nowUnixSec"
        lines += "stage=${result.stage} failedStage=${result.failedStage ?: "-"} " +
            "viaGateway=${result.viaGateway} tunnelState=${result.tunnelState}"
        result.stageDetail?.takeIf { it.isNotBlank() }?.let {
            lines += "detail=${SpeedTroubleshooter.sanitizeMessage(it)}"
        }
        result.errorMessage?.takeIf { it.isNotBlank() }?.let {
            lines += "error=${SpeedTroubleshooter.sanitizeMessage(it)}"
        }
        lines += String.format(
            Locale.US,
            "result: pingMs=%d jitterMs=%d downloadMbps=%.1f uploadMbps=%.1f",
            result.pingMs,
            result.jitterMs,
            result.downloadMbps,
            result.uploadMbps
        )
        lines += formatTunnel(result.metricsSnapshot, nowUnixSec)
        lines += "findings:"
        if (result.findings.isEmpty()) {
            lines += "(none)"
        } else {
            result.findings.forEach { finding ->
                lines += "[${finding.severity}] ${finding.message}"
            }
        }
        return lines.joinToString("\n")
    }

    private fun formatTunnel(metrics: NetworkMetrics?, nowUnixSec: Long): String {
        if (metrics == null) return "tunnel: unavailable"
        val live = metrics.isLiveRunning(nowUnixSec)
        val healthAge = if (metrics.healthUnixSec > 0) nowUnixSec - metrics.healthUnixSec else -1L
        val drops = metrics.dropCounters
        val dropLine = String.format(
            Locale.US,
            "drops: malformed=%d mtu=%d queue=%d policy=%d",
            drops.malformedIp,
            drops.mtuExceeded,
            drops.queueExhaustion,
            drops.policyRejections
        )
        val egressErr = metrics.egressAuditError
            ?.let { SpeedTroubleshooter.sanitizeMessage(it) }
            ?.takeIf { it.isNotBlank() }
        return buildString {
            append("tunnel: state=")
            append(metrics.state.ifBlank { "unknown" })
            append(" live=")
            append(live)
            append(" transport=")
            append(metrics.transportType)
            append(" derp=")
            append(metrics.derpRegionCode ?: metrics.derpRegionId ?: "-")
            append(" tcpOnly=")
            append(metrics.tcpOnly)
            append(" ipv6Egress=")
            append(metrics.ipv6Egress)
            append(" discoStale=")
            append(metrics.discoStale)
            append(" rttMs=")
            append(metrics.rttLatencyMs)
            append(" healthAgeSec=")
            append(healthAge)
            if (egressErr != null) {
                append(" egressAuditErr=")
                append(egressErr)
            }
            append('\n')
            append(dropLine)
        }
    }
}
