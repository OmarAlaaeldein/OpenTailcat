package com.tailcat.vpn

import com.tailcat.vpn.core.model.NetworkMetrics
import com.tailcat.vpn.core.model.TransportType
import com.tailcat.vpn.service.EngineCapabilities
import com.tailcat.vpn.service.EngineHealth
import com.tailcat.vpn.service.TunnelEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TunnelEngineTest {

    @Test
    fun testEngineCapabilitiesParsingV1LegacyFailsClosed() {
        val legacyV1Json = """{"apiVersion":1,"dataPlane":true,"wireGuard":true,"magicsock":true,"twoPhaseStart":true}"""
        val caps = EngineCapabilities.fromJson(legacyV1Json)

        assertEquals(1, caps.apiVersion)
        assertTrue(caps.dataPlane)
        assertFalse(caps.ipv4)
        assertFalse(caps.udp)
        // Must fail closed because apiVersion < 2 and individual data-plane capabilities are missing
        assertFalse(caps.satisfiesRouteRequirements())
    }

    @Test
    fun testEngineCapabilitiesIncompleteV2FailsClosed() {
        val incompleteV2Json = """{
            "apiVersion": 2,
            "dataPlane": false,
            "wireGuard": false,
            "magicsock": false,
            "twoPhaseStart": true,
            "ipv4": false,
            "ipv6": true,
            "tcp": false,
            "udp": false,
            "dns": false,
            "liveStats": false,
            "cancelSafeLifecycle": false
        }"""
        val caps = EngineCapabilities.fromJson(incompleteV2Json)

        assertEquals(2, caps.apiVersion)
        assertFalse(caps.dataPlane)
        assertTrue(caps.twoPhaseStart)
        assertFalse(caps.satisfiesRouteRequirements())
    }

    @Test
    fun testEngineCapabilitiesUnknownFieldsFailClosed() {
        val json = """{
            "apiVersion": 2,
            "dataPlane": true,
            "wireGuard": true,
            "magicsock": true,
            "twoPhaseStart": true,
            "ipv4": true,
            "ipv6": true,
            "tcp": true,
            "udp": true,
            "dns": true,
            "liveStats": true,
            "cancelSafeLifecycle": true,
            "experimentalLeak": true
        }"""
        try {
            EngineCapabilities.fromJson(json)
            fail("Expected unknown capability fields to fail closed")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("unknown capability fields") == true)
            assertTrue(e.message?.contains("experimentalLeak") == true)
        }
    }

    @Test
    fun testEngineCapabilitiesTestRoutingIsParsedAndOptional() {
        val withFlag = """{
            "apiVersion": 2,
            "dataPlane": true,
            "wireGuard": true,
            "magicsock": true,
            "twoPhaseStart": true,
            "ipv4": true,
            "ipv6": true,
            "tcp": true,
            "udp": true,
            "dns": true,
            "liveStats": true,
            "cancelSafeLifecycle": true,
            "testRouting": true
        }"""
        val caps = EngineCapabilities.fromJson(withFlag)
        assertTrue(caps.testRouting)
        assertTrue(caps.satisfiesRouteRequirements())

        val legacy = """{
            "apiVersion": 2,
            "dataPlane": true,
            "wireGuard": true,
            "magicsock": true,
            "twoPhaseStart": true,
            "ipv4": true,
            "ipv6": true,
            "tcp": true,
            "udp": true,
            "dns": true,
            "liveStats": true,
            "cancelSafeLifecycle": true
        }"""
        assertFalse(EngineCapabilities.fromJson(legacy).testRouting)
        assertTrue(EngineCapabilities.fromJson(legacy).satisfiesRouteRequirements())
    }

    @Test
    fun testEngineCapabilitiesIndividualMissingFlagsFailClosed() {
        // Base complete map
        val baseFlags = mutableMapOf(
            "apiVersion" to "2",
            "dataPlane" to "true",
            "wireGuard" to "true",
            "magicsock" to "true",
            "twoPhaseStart" to "true",
            "ipv4" to "true",
            "ipv6" to "true",
            "tcp" to "true",
            "udp" to "true",
            "dns" to "true",
            "liveStats" to "true",
            "cancelSafeLifecycle" to "true"
        )

        // Verifying base is complete for IPv4
        val completeV2Json = baseFlags.entries.joinToString(prefix = "{", postfix = "}") {
            "\"${it.key}\": ${it.value}"
        }
        assertTrue(EngineCapabilities.fromJson(completeV2Json).satisfiesRouteRequirements(requireIpv6 = false))
        assertTrue(EngineCapabilities.fromJson(completeV2Json).satisfiesRouteRequirements(requireIpv6 = true))
        assertTrue(EngineCapabilities.fromJson(completeV2Json).satisfiesRouteRequirements())

        // Each required flag turned to false must fail closed
        val requiredFlags = listOf(
            "dataPlane", "wireGuard", "magicsock", "twoPhaseStart",
            "ipv4", "ipv6", "tcp", "udp", "dns", "liveStats", "cancelSafeLifecycle"
        )

        for (flag in requiredFlags) {
            val mutated = baseFlags.toMutableMap()
            mutated[flag] = "false"
            val json = mutated.entries.joinToString(prefix = "{", postfix = "}") {
                "\"${it.key}\": ${it.value}"
            }
            val caps = EngineCapabilities.fromJson(json)
            assertFalse("Flag $flag set to false must fail route requirements", caps.satisfiesRouteRequirements())
        }
    }

    @Test
    fun testCurrentEngineIsUnavailableFailClosed() {
        val engine = TunnelEngine()
        val availability = engine.availability

        // Host JVM cannot load the Android JNI engine, so unit tests stay unavailable.
        assertNotNull(availability)
        assertNotNull(availability.message)
        assertFalse("Host unit tests cannot load native JNI; engine must stay unavailable here", availability.isAvailable)
    }

    @Test
    fun testPrepareFailsClosedWhenUnavailable() {
        val engine = TunnelEngine()
        assertFalse(engine.availability.isAvailable)

        try {
            engine.prepare("tcTestToken")
            fail("Expected prepare to fail when engine is unavailable")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("VPN engine") == true)
        }
    }

    @Test
    fun testAttachTunFailsClosedWhenUnavailable() {
        val engine = TunnelEngine()
        assertFalse(engine.availability.isAvailable)

        try {
            engine.attachTun(42)
            fail("Expected attachTun to fail when engine is unavailable")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("VPN engine") == true)
        }
    }

    @Test
    fun testGetStatsFailsClosedWhenUnavailable() {
        val engine = TunnelEngine()
        assertFalse(engine.availability.isAvailable)

        try {
            engine.getStats()
            fail("Expected getStats to fail when engine is unavailable")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("VPN engine") == true)
        }
    }

    @Test
    fun testUpdateNetworkStateSafeWhenUnavailable() {
        val engine = TunnelEngine()
        // updateNetworkState should be safe to call at any time without crashing
        engine.updateNetworkState("""{"isOnline":true,"networkType":"WIFI","interfaces":[]}""")
        engine.updateNetworkState("")
    }

    @Test
    fun testStopLifecycleIdempotent() {
        val engine = TunnelEngine()
        engine.stop()
        engine.stop()
    }

    @Test
    fun testDetachTunSafeWhenUnavailable() {
        val engine = TunnelEngine()
        engine.detachTun()
        engine.detachTun()
    }

    @Test
    fun testDisarmPumpsSafeWhenUnavailable() {
        val engine = TunnelEngine()
        engine.disarmPumps()
        engine.disarmPumps()
    }

    @Test
    fun testNetworkMetricsVersion2Parsing() {
        val v2Json = """{
            "version": 2,
            "sessionId": 42,
            "state": "RUNNING",
            "healthUnixSec": 1725301300,
            "transport": "DIRECT_P2P",
            "derpRegionId": 302,
            "derpRegionCode": "sfo",
            "derpRegionName": "San Francisco",
            "tunnelEgressIp": "203.0.113.88",
            "rttMs": 18,
            "jitterMs": 3,
            "tunTxBytes": 480000,
            "tunRxBytes": 870000,
            "txBytes": 500000,
            "rxBytes": 900000,
            "txRateKbps": 120,
            "rxRateKbps": 450,
            "tcpPackets": 1200,
            "udpPackets": 340,
            "dnsQueries": 88,
            "dropCounters": {
                "malformedIp": 2,
                "mtuExceeded": 1,
                "queueExhaustion": 0,
                "policyRejections": 0,
                "linkQueueDrops": 4,
                "udpBufferDrops": 5
            },
            "egressAuditTimestampSec": 1725301200,
            "egressAuditError": null
        }"""

        val metrics = NetworkMetrics.fromJson(v2Json)

        assertEquals(2, metrics.version)
        assertEquals(42L, metrics.sessionId)
        assertEquals("RUNNING", metrics.state)
        assertEquals(1725301300L, metrics.healthUnixSec)
        assertEquals(TransportType.DIRECT_P2P, metrics.transportType)
        assertEquals(302, metrics.derpRegionId)
        assertEquals("sfo", metrics.derpRegionCode)
        assertEquals("San Francisco", metrics.derpRegionName)
        assertEquals("203.0.113.88", metrics.tunnelEgressIp)
        assertEquals(18L, metrics.rttLatencyMs)
        assertEquals(3L, metrics.jitterMs)
        assertEquals(480000L, metrics.tunTxBytes)
        assertEquals(870000L, metrics.tunRxBytes)
        assertEquals(1200L, metrics.tcpPackets)
        assertEquals(340L, metrics.udpPackets)
        assertEquals(88L, metrics.dnsQueries)
        assertEquals(2L, metrics.dropCounters.malformedIp)
        assertEquals(1L, metrics.dropCounters.mtuExceeded)
        assertEquals(0L, metrics.dropCounters.queueExhaustion)
        assertEquals(0L, metrics.dropCounters.policyRejections)
        assertEquals(4L, metrics.dropCounters.linkQueueDrops)
        assertEquals(5L, metrics.dropCounters.udpBufferDrops)
        assertEquals(12L, metrics.dropCounters.total)
        // discoStale / ipv6Egress absent in this payload must default to false
        assertFalse(metrics.discoStale)
        assertFalse(metrics.ipv6Egress)
        assertEquals(1725301200L, metrics.egressAuditTimestampSec)
    }

    @Test
    fun testNetworkMetricsNullJitterParsing() {
        val json = """{
            "version": 2,
            "sessionId": 1,
            "state": "RUNNING",
            "transport": "DERP_RELAY",
            "derpRegionId": 1,
            "derpRegionName": "New York City",
            "rttMs": 40,
            "jitterMs": null,
            "txBytes": 100,
            "rxBytes": 200
        }"""

        val metrics = NetworkMetrics.fromJson(json)
        assertEquals(40L, metrics.rttLatencyMs)
        assertEquals(null, metrics.jitterMs)
    }

    @Test
    fun testNetworkMetricsDiscoStaleParsedWhenPresent() {
        val json = """{
            "version": 2,
            "sessionId": 7,
            "state": "RUNNING",
            "transport": "DERP_RELAY",
            "rttMs": 55,
            "dnsQueries": 12,
            "discoStale": true,
            "lastDiscoOkUnixSec": 1700000000,
            "dropCounters": {
                "malformedIp": 0,
                "mtuExceeded": 0,
                "queueExhaustion": 1,
                "policyRejections": 3,
                "udpEvictions": 9
            }
        }"""

        val metrics = NetworkMetrics.fromJson(json)
        assertTrue(metrics.discoStale)
        assertEquals(1_700_000_000L, metrics.lastDiscoOkUnixSec)
        assertEquals(12L, metrics.dnsQueries)
        assertEquals(1L, metrics.dropCounters.queueExhaustion)
        assertEquals(3L, metrics.dropCounters.policyRejections)
    }

    @Test
    fun testNetworkMetricsDiscoStaleDefaultsFalseWhenAbsent() {
        val json = """{
            "version": 2,
            "sessionId": 8,
            "state": "RUNNING",
            "transport": "DIRECT_P2P",
            "rttMs": 18,
            "dnsQueries": 5,
            "dropCounters": {
                "malformedIp": 0,
                "mtuExceeded": 0,
                "queueExhaustion": 0,
                "policyRejections": 0
            }
        }"""

        val metrics = NetworkMetrics.fromJson(json)
        assertFalse(metrics.discoStale)
        assertEquals(5L, metrics.dnsQueries)
        assertEquals(0L, metrics.dropCounters.policyRejections)
    }

    @Test
    fun testNetworkMetricsV1Rejected() {
        val v1Json = """{"version": 1, "transport": "DERP_RELAY", "state": "RUNNING"}"""
        try {
            NetworkMetrics.fromJson(v1Json)
            fail("Expected unsupported schema version 1 to fail")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("Unsupported telemetry schema version") == true)
        }
    }

    @Test
    fun testNetworkMetricsMissingVersionRejected() {
        val missingVersionJson = """{"transport": "DERP_RELAY", "state": "RUNNING"}"""
        try {
            NetworkMetrics.fromJson(missingVersionJson)
            fail("Expected missing telemetry schema version to fail")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("Unsupported telemetry schema version") == true)
        }
    }

    @Test
    fun testNetworkMetricsMissingStateIsEmptyNotRunning() {
        val json = """{
            "version": 2,
            "sessionId": 1,
            "transport": "DERP_RELAY",
            "derpRegionId": 1,
            "rttMs": 40
        }"""
        val metrics = NetworkMetrics.fromJson(json)
        assertEquals("", metrics.state)
        assertEquals(TransportType.DERP_RELAY, metrics.transportType)
    }

    @Test
    fun testNetworkMetricsHealthUnixSecParsedAndMissingIsZero() {
        val withHealth = """{
            "version": 2,
            "state": "RUNNING",
            "transport": "DIRECT_P2P",
            "healthUnixSec": 1725301300
        }"""
        val parsed = NetworkMetrics.fromJson(withHealth)
        assertEquals(1725301300L, parsed.healthUnixSec)

        val missingHealth = """{
            "version": 2,
            "state": "RUNNING",
            "transport": "DIRECT_P2P"
        }"""
        val missing = NetworkMetrics.fromJson(missingHealth)
        assertEquals(0L, missing.healthUnixSec)
    }

    @Test
    fun testNetworkMetricsIsLiveRunningRequiresRunningAndFreshHealth() {
        val live = NetworkMetrics(
            state = "RUNNING",
            healthUnixSec = 1000L
        )
        assertTrue(live.isLiveRunning(nowUnixSec = 1004L))
        assertTrue(live.isLiveRunning(nowUnixSec = 1005L))
        // 6s age must remain live (regression for HealthStale auto-close).
        assertTrue(live.isLiveRunning(nowUnixSec = 1006L))
        assertTrue(live.isLiveRunning(nowUnixSec = 1015L))
        assertFalse(live.isLiveRunning(nowUnixSec = 1016L))

        val stale = NetworkMetrics(
            state = "RUNNING",
            healthUnixSec = 1000L
        )
        assertFalse(stale.isLiveRunning(nowUnixSec = 2000L))

        val noHealth = NetworkMetrics(
            state = "RUNNING",
            healthUnixSec = 0L
        )
        assertFalse(noHealth.isLiveRunning(nowUnixSec = 1000L))

        val notRunning = NetworkMetrics(
            state = "PREPARED",
            healthUnixSec = 1000L
        )
        assertFalse(notRunning.isLiveRunning(nowUnixSec = 1000L))

        val missingState = NetworkMetrics(
            state = "",
            healthUnixSec = 1000L
        )
        assertFalse(missingState.isLiveRunning(nowUnixSec = 1000L))

        val slightFuture = NetworkMetrics(
            state = "RUNNING",
            healthUnixSec = 1002L
        )
        assertTrue(slightFuture.isLiveRunning(nowUnixSec = 1000L))

        val farFuture = NetworkMetrics(
            state = "RUNNING",
            healthUnixSec = 1000L + NetworkMetrics.MAX_HEALTH_FUTURE_SKEW_SEC + 5L
        )
        assertFalse(farFuture.isLiveRunning(nowUnixSec = 1000L))
    }

    @Test
    fun testNetworkMetricsIncompatibleVersionRejection() {
        val v3Json = """{"version": 3, "transport": "DIRECT_P2P"}"""
        try {
            NetworkMetrics.fromJson(v3Json)
            fail("Expected unsupported schema version 3 to fail")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("Unsupported telemetry schema version") == true)
        }
    }

    @Test
    fun testEngineHealthConnectAndTearDownGates() {
        val live = NetworkMetrics(
            state = "RUNNING",
            healthUnixSec = 1000L,
            transportType = TransportType.DERP_RELAY
        )
        assertTrue(EngineHealth.shouldConnect(live, 1000L))
        assertEquals(EngineHealth.TeardownReason.Healthy, EngineHealth.teardownReason(live, 1000L))

        val unknownTransport = live.copy(transportType = TransportType.UNKNOWN)
        assertFalse(EngineHealth.shouldConnect(unknownTransport, 1000L))

        val failed = live.copy(state = "FAILED")
        assertFalse(EngineHealth.shouldConnect(failed, 1000L))
        assertTrue(EngineHealth.teardownReason(failed, 1000L) is EngineHealth.TeardownReason.PumpFailed)

        val stale = live.copy(healthUnixSec = 1L)
        assertFalse(EngineHealth.shouldConnect(stale, 1000L))
        assertTrue(EngineHealth.teardownReason(stale, 1000L) is EngineHealth.TeardownReason.HealthStale)

        val prepared = live.copy(state = "PREPARED")
        assertFalse(EngineHealth.shouldConnect(prepared, 1000L))
        assertTrue(EngineHealth.teardownReason(prepared, 1000L) is EngineHealth.TeardownReason.HealthStale)
    }
}


