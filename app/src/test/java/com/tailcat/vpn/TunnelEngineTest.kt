package com.tailcat.vpn

import com.tailcat.vpn.service.EngineCapabilities
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
            "ipv6": false,
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
    fun testEngineCapabilitiesIndividualMissingFlagsFailClosed() {
        // Base complete map
        val baseFlags = mutableMapOf(
            "apiVersion" to "2",
            "dataPlane" to "true",
            "wireGuard" to "true",
            "magicsock" to "true",
            "twoPhaseStart" to "true",
            "ipv4" to "true",
            "ipv6" to "false",
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
        assertFalse(EngineCapabilities.fromJson(completeV2Json).satisfiesRouteRequirements(requireIpv6 = true))

        // Each required flag turned to false must fail closed
        val requiredFlags = listOf(
            "dataPlane", "wireGuard", "magicsock", "twoPhaseStart",
            "ipv4", "tcp", "udp", "dns", "liveStats", "cancelSafeLifecycle"
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

        // Incomplete engine or host environment must report isAvailable == false
        assertNotNull(availability)
        assertNotNull(availability.message)
        assertFalse("Incomplete engine must not be available for route installation", availability.isAvailable)
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
    fun testStopLifecycleIdempotent() {
        val engine = TunnelEngine()
        // Stop should be safe and idempotent regardless of engine availability
        engine.stop()
        engine.stop()
    }
}


