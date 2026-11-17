package com.tailcat.vpn.service

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
