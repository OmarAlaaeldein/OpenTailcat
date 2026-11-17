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
    fun aShareWithoutATimeLimitSendsNoExpiryAndNoTtl() {
        val json = JSONObject(TunnelExitNode.configJson(ExitNodeConfig(null, ExitNodeAdmission.APPROVAL)))
        assertTrue(json.getBoolean("noExpiry"))
        assertEquals("approval", json.getString("admission"))
        assertEquals(setOf("noExpiry", "admission"), json.keys().asSequence().toSet())
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
