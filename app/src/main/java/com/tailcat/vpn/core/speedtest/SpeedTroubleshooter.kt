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

    private const val REDACTED = "[ip-redacted]"

    // Runs of characters an IP literal can contain; each run is validated as
    // a literal so a neighbouring word character cannot leave part of an
    // address behind (e.g. "x2001:db8::1", "ip_203.0.113.5").
    private val CANDIDATE = Regex("""[0-9A-Fa-f:.]+""")

    // IPv4 written with dashes inside hostnames (ec2-203-0-113-5.compute...).
    private val DASHED_IPV4 = Regex("""(?<![0-9])[0-9]{1,3}(?:-[0-9]{1,3}){3}(?![0-9])""")

    private val PRIVATE_HINT = Regex(
        """(?:10\.\d{1,3}\.\d{1,3}\.\d{1,3}|192\.168\.\d{1,3}\.\d{1,3}|127\.\d{1,3}\.\d{1,3}\.\d{1,3}|172\.(?:1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3})"""
    )

    private val IPV4_WITH_PORT = Regex("""(.+):([0-9]{1,5})""")

    fun sanitizeMessage(raw: String?): String {
        if (raw.isNullOrBlank()) return raw.orEmpty()
        val undashed = DASHED_IPV4.replace(raw) { m ->
            if (isIpv4(m.value.replace('-', '.'))) REDACTED else m.value
        }
        return CANDIDATE.replace(undashed) { m -> redactToken(m.value) }
    }

    private fun redactToken(token: String): String {
        if (isIpv6(token)) return REDACTED
        val core = token.trimEnd('.')
        val suffix = token.substring(core.length)
        if (isIpv4(core)) return keepPrivate(core) + suffix
        IPV4_WITH_PORT.matchEntire(core)?.let { m ->
            if (isIpv4(m.groupValues[1])) return keepPrivate(m.groupValues[1]) + ":" + m.groupValues[2] + suffix
        }
        return token
    }

    private fun keepPrivate(ipv4: String): String =
        if (PRIVATE_HINT.matches(ipv4)) ipv4 else REDACTED

    private fun isIpv4(s: String): Boolean {
        val parts = s.split('.')
        return parts.size == 4 && parts.all { p ->
            p.length in 1..3 && p.all(Char::isDigit) && p.toInt() <= 255
        }
    }

    private fun isHextet(s: String): Boolean =
        s.length in 1..4 && s.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }

    /** Literal IPv6 check (no resolver involved): hextets, one "::", optional IPv4 tail. */
    private fun isIpv6(s: String): Boolean {
        if (s.count { it == ':' } < 2) return false
        var body = s
        var tailGroups = 0
        val lastColon = body.lastIndexOf(':')
        val tail = body.substring(lastColon + 1)
        if (tail.contains('.')) {
            if (!isIpv4(tail)) return false
            body = body.substring(0, lastColon)
            tailGroups = 2
            if (body.endsWith(':')) body += ":" // "::1.2.3.4" keeps its "::"
        }
        val first = body.indexOf("::")
        if (first != body.lastIndexOf("::")) return false
        if (first < 0) {
            val groups = body.split(':')
            return groups.size + tailGroups == 8 && groups.all(::isHextet)
        }
        val left = body.substring(0, first).let { if (it.isEmpty()) emptyList() else it.split(':') }
        val right = body.substring(first + 2).let { if (it.isEmpty()) emptyList() else it.split(':') }
        return (left + right).all(::isHextet) && left.size + right.size + tailGroups <= 7
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
                            "The gateway did not answer recent discovery pings — the shown RTT and path are not current"
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
        } else if (tunnelState != TunnelState.DISCONNECTED) {
            findings += TroubleshootFinding(
                FindingSeverity.WARNING,
                "The tunnel was up but not CONNECTED — this run used the device routes, which go into the VPN interface; it is not a gateway measurement"
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
