package com.tailcat.vpn.core.speedtest

import com.tailcat.vpn.core.model.DropCounters
import com.tailcat.vpn.core.model.NetworkMetrics
import com.tailcat.vpn.core.model.TunnelState
import com.tailcat.vpn.core.model.TransportType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedTroubleshooterTest {

    private fun liveMetrics(
        discoStale: Boolean = false,
        transport: TransportType = TransportType.DIRECT_P2P,
        tcpOnly: Boolean = false,
        rttMs: Long = 40,
        drops: DropCounters = DropCounters(),
        egressAuditError: String? = null,
        ipv6Egress: Boolean = true,
        now: Long = 1_000L
    ) = NetworkMetrics(
        state = "RUNNING",
        healthUnixSec = now,
        transportType = transport,
        tcpOnly = tcpOnly,
        ipv6Egress = ipv6Egress,
        rttLatencyMs = rttMs,
        discoStale = discoStale,
        dropCounters = drops,
        egressAuditError = egressAuditError
    )

    @Test
    fun sanitizesPublicIpsButKeepsPrivateHints() {
        val raw = "dial 203.0.113.9 via 10.0.2.15 failed"
        val out = SpeedTroubleshooter.sanitizeMessage(raw)
        assertTrue(out.contains("[ip-redacted]"))
        assertTrue(out.contains("10.0.2.15"))
        assertFalse(out.contains("203.0.113.9"))
    }

    @Test
    fun failedStageReportsErrorAndDetail() {
        val findings = SpeedTroubleshooter.diagnose(
            tunnelState = TunnelState.CONNECTED,
            metrics = liveMetrics(),
            stage = SpeedTestStage.FAILED,
            errorMessage = "Download test failed: timeout",
            viaGateway = true,
            nowUnixSec = 1_000L,
            stageDetail = "gateway download via Client.DialTCP"
        )
        assertTrue(findings.any { it.severity == FindingSeverity.ERROR })
        assertTrue(findings.any { it.message.contains("gateway download") })
    }

    @Test
    fun deadHealthSurfacesErrorOnGatewayPath() {
        val metrics = NetworkMetrics(state = "FAILED", healthUnixSec = 1L)
        val findings = SpeedTroubleshooter.diagnose(
            tunnelState = TunnelState.CONNECTED,
            metrics = metrics,
            stage = SpeedTestStage.FAILED,
            errorMessage = "Tunnel ping failed",
            viaGateway = true,
            nowUnixSec = 1_000L
        )
        assertTrue(findings.any {
            it.severity == FindingSeverity.ERROR && it.message.contains("health is not live")
        })
    }

    @Test
    fun sanitizerRedactsWholeAddressesAndKeepsTimes() {
        val redacted = listOf(
            "203.0.113.5:443",
            "[2606:4700:4700::1111]:443",
            "::ffff:203.0.113.5",
            "2001:db8::",
            "2001:db8::/32",
            "x2001:db8::1",
            "ip_203.0.113.5",
            "ec2-203-0-113-5.compute.amazonaws.com",
            "fe80::1%wlan0",
            "2001:0db8:0000:0000:0000:ff00:0042:8329",
            "reached 198.51.100.7."
        )
        for (raw in redacted) {
            val out = SpeedTroubleshooter.sanitizeMessage(raw)
            assertTrue("$raw -> $out", out.contains("[ip-redacted]"))
            for (leak in listOf("203", "2001", "2606", "198.51", "fe80", "db8")) {
                assertFalse("$raw leaked $leak: $out", out.contains(leak))
            }
        }
        assertEquals("at 12:34:56 took 1.5 s", SpeedTroubleshooter.sanitizeMessage("at 12:34:56 took 1.5 s"))
        assertEquals("via 10.0.2.15:8080", SpeedTroubleshooter.sanitizeMessage("via 10.0.2.15:8080"))
    }

    @Test
    fun halfUpTunnelWarnsThatRunWasNotAGatewayMeasurement() {
        val findings = SpeedTroubleshooter.diagnose(
            tunnelState = TunnelState.RECONNECTING,
            metrics = null,
            stage = SpeedTestStage.COMPLETED,
            errorMessage = null,
            viaGateway = false,
            nowUnixSec = 1_000L
        )
        assertTrue(findings.any { it.message.contains("not a gateway measurement") })
    }

    @Test
    fun discoStaleDerpTcpOnlyAndDropsWarn() {
        val findings = SpeedTroubleshooter.diagnose(
            tunnelState = TunnelState.CONNECTED,
            metrics = liveMetrics(
                discoStale = true,
                transport = TransportType.DERP_RELAY,
                tcpOnly = true,
                rttMs = 300,
                drops = DropCounters(queueExhaustion = 3L, policyRejections = 1L),
                egressAuditError = "probe 198.51.100.7 refused"
            ),
            stage = SpeedTestStage.COMPLETED,
            errorMessage = null,
            viaGateway = true,
            nowUnixSec = 1_000L
        )
        assertTrue(findings.any { it.message.contains("did not answer recent discovery pings") })
        assertTrue(findings.any { it.message.contains("DERP") })
        assertTrue(findings.any { it.message.contains("TCP-only") })
        assertTrue(findings.any { it.message.contains("250") || it.message.contains("300") })
        assertTrue(findings.any { it.message.contains("queue=3") })
        assertTrue(findings.any { it.message.contains("198.51.100.7").not() })
        assertTrue(findings.any { it.message.contains("[ip-redacted]") })
    }

    @Test
    fun healthyDirectGatewayRunHasNoErrorFindings() {
        val findings = SpeedTroubleshooter.diagnose(
            tunnelState = TunnelState.CONNECTED,
            metrics = liveMetrics(),
            stage = SpeedTestStage.COMPLETED,
            errorMessage = null,
            viaGateway = true,
            nowUnixSec = 1_000L
        )
        assertTrue(findings.none { it.severity == FindingSeverity.ERROR })
    }

    @Test
    fun physicalPathWithoutTunnelDoesNotDemandMetrics() {
        val findings = SpeedTroubleshooter.diagnose(
            tunnelState = TunnelState.DISCONNECTED,
            metrics = null,
            stage = SpeedTestStage.COMPLETED,
            errorMessage = null,
            viaGateway = false,
            nowUnixSec = 1_000L
        )
        assertTrue(findings.isEmpty())
    }
}
