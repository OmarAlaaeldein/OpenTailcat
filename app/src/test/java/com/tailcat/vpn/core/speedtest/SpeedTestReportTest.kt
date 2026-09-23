package com.tailcat.vpn.core.speedtest

import com.tailcat.vpn.core.model.DropCounters
import com.tailcat.vpn.core.model.NetworkMetrics
import com.tailcat.vpn.core.model.TunnelState
import com.tailcat.vpn.core.model.TransportType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedTestReportTest {

    private fun sampleMetrics(now: Long = 1_000L) = NetworkMetrics(
        state = "RUNNING",
        healthUnixSec = now,
        transportType = TransportType.DIRECT_P2P,
        tcpOnly = false,
        ipv6Egress = true,
        derpRegionCode = "nyc",
        rttLatencyMs = 42,
        discoStale = false,
        dropCounters = DropCounters(malformedIp = 1, mtuExceeded = 2),
        egressAuditError = null
    )

    @Test
    fun buildsReportWithoutPublicIpsOrTokens() {
        val result = SpeedTestResult(
            stage = SpeedTestStage.COMPLETED,
            pingMs = 40,
            jitterMs = 3,
            downloadMbps = 123.4,
            uploadMbps = 56.7,
            viaGateway = true,
            stageDetail = "gateway download via Client.DialTCP",
            findings = listOf(
                TroubleshootFinding(FindingSeverity.WARNING, "Disco path is stale")
            ),
            metricsSnapshot = sampleMetrics(),
            tunnelState = TunnelState.CONNECTED
        )
        val report = SpeedTestReport.build(result, nowUnixSec = 1_000L, appVersion = "1.3.7")

        assertTrue(report.contains("OpenTailcat troubleshooter report"))
        assertTrue(report.contains("app=1.3.7"))
        assertTrue(report.contains("stage=COMPLETED"))
        assertTrue(report.contains("viaGateway=true"))
        assertTrue(report.contains("downloadMbps=123.4"))
        assertTrue(report.contains("transport=DIRECT_P2P"))
        assertTrue(report.contains("derp=nyc"))
        assertTrue(report.contains("drops: malformed=1 mtu=2"))
        assertTrue(report.contains("[WARNING] Disco path is stale"))
        assertFalse(report.contains("tco2"))
    }

    @Test
    fun sanitizesErrorMessageInReport() {
        val result = SpeedTestResult(
            stage = SpeedTestStage.FAILED,
            errorMessage = "dial 198.51.100.10 failed",
            failedStage = SpeedTestStage.TESTING_DOWNLOAD,
            metricsSnapshot = null,
            tunnelState = TunnelState.DISCONNECTED
        )
        val report = SpeedTestReport.build(result, nowUnixSec = 2_000L, appVersion = "1.3.7")

        assertTrue(report.contains("error="))
        assertTrue(report.contains("[ip-redacted]"))
        assertFalse(report.contains("198.51.100.10"))
        assertTrue(report.contains("failedStage=TESTING_DOWNLOAD"))
        assertTrue(report.contains("tunnel: unavailable"))
    }

    @Test
    fun includesEmptyFindingsPlaceholder() {
        val report = SpeedTestReport.build(
            result = SpeedTestResult(stage = SpeedTestStage.COMPLETED),
            nowUnixSec = 0L,
            appVersion = "0.0.1"
        )
        assertTrue(report.contains("findings:"))
        assertTrue(report.contains("(none)"))
    }
}
