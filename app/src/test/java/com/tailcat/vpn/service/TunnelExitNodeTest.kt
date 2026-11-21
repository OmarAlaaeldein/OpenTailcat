package com.tailcat.vpn.service

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelExitNodeTest {

    @Test
    fun configJsonMatchesTheNativeContract() {
        val approval = JSONObject(TunnelExitNode.configJson(ExitNodeConfig(3600, ExitNodeAdmission.APPROVAL)))
        assertEquals(3600L, approval.getLong("ttlSeconds"))
        assertEquals("approval", approval.getString("admission"))
        assertEquals("only the fields Go accepts", setOf("ttlSeconds", "admission"), approval.keys().asSequence().toSet())

        val open = JSONObject(TunnelExitNode.configJson(ExitNodeConfig(60, ExitNodeAdmission.OPEN)))
        assertEquals("open", open.getString("admission"))
    }

    @Test
    fun aShareWithoutATimeLimitSendsNoExpiryAndNoTtl() {        val json = JSONObject(TunnelExitNode.configJson(ExitNodeConfig(null, ExitNodeAdmission.APPROVAL)))
        assertTrue(json.getBoolean("noExpiry"))
        assertEquals("approval", json.getString("admission"))
        assertEquals(setOf("noExpiry", "admission"), json.keys().asSequence().toSet())
    }

    @Test
    fun aCustomRelayIsSentUnderRelay() {
        val relay = ExitNodeRelay("derp.example.com", derpPort = 8443, stunPort = 3479)
        val json = JSONObject(TunnelExitNode.configJson(ExitNodeConfig(60, relay = relay)))
        val wire = json.getJSONObject("relay")
        assertEquals("derp.example.com", wire.getString("hostname"))
        assertEquals(8443, wire.getInt("derpPort"))
        assertEquals(3479, wire.getInt("stunPort"))
        assertEquals(
            "only the fields Go accepts",
            setOf("ttlSeconds", "admission", "relay"),
            json.keys().asSequence().toSet()
        )

        val defaults = JSONObject(TunnelExitNode.configJson(ExitNodeConfig(60, relay = ExitNodeRelay("derp.example.com"))))
            .getJSONObject("relay")
        assertEquals(443, defaults.getInt("derpPort"))
        assertEquals(3478, defaults.getInt("stunPort"))
    }

    @Test
    fun relayHostsAreValidatedBeforeTheEngineSeesThem() {
        assertEquals("Enter your relay's host name", ExitNodeRelay.validateHostname("   "))
        assertTrue(ExitNodeRelay.validateHostname("https://derp.example.com")!!.contains("host name only"))
        assertTrue(ExitNodeRelay.validateHostname("derp.example.com:8443")!!.contains("host name only"))
        assertTrue(ExitNodeRelay.validateHostname("203.0.113.10")!!.contains("not an IP address"))
        assertTrue(ExitNodeRelay.validateHostname("localhost")!!.contains("localhost"))
        assertEquals(null, ExitNodeRelay.validateHostname(" derp.example.com "))

        assertEquals(null, ExitNodeRelay.problem("derp.example.com", "443", "3478"))
        assertTrue(ExitNodeRelay.problem("derp.example.com", "0", "3478")!!.contains("DERP port"))
        assertTrue(ExitNodeRelay.problem("derp.example.com", "443", "70000")!!.contains("STUN port"))
        assertThrows(IllegalArgumentException::class.java) {
            ExitNodeRelay("derp.example.com", derpPort = 0)
        }
    }

    @Test
    fun aShareWithoutATimeLimitMustUseApproval() {
        assertThrows(IllegalArgumentException::class.java) { ExitNodeConfig(null, ExitNodeAdmission.OPEN) }
        assertFalse(ExitNodeConfig(null).hasTimeLimit)
        assertTrue(ExitNodeConfig(60).hasTimeLimit)
    }

    @Test
    fun theDefaultAdmissionIsApproval() {
        assertEquals(ExitNodeAdmission.APPROVAL, ExitNodeConfig(60).admission)
    }

    @Test
    fun onAHostJvmTheEngineIsReportedUnavailableNotCrashed() {
        // The Go library cannot load here; the facade must say so, not throw.
        val node = TunnelExitNode()
        assertFalse(node.availability.isAvailable)
        node.stop() // must be a no-op
    }
}
