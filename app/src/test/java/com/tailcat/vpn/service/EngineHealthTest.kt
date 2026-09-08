package com.tailcat.vpn.service

import com.tailcat.vpn.core.model.DropCounters
import com.tailcat.vpn.core.model.NetworkMetrics
import com.tailcat.vpn.core.model.TransportType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineHealthTest {

    private fun metrics(
        state: String = "RUNNING",
        transport: TransportType = TransportType.DERP_RELAY,
        healthUnixSec: Long = 1_000L,
        egressErr: String? = null
    ) = NetworkMetrics(
        state = state,
        transportType = transport,
        healthUnixSec = healthUnixSec,
        egressAuditError = egressErr,
        dropCounters = DropCounters()
    )

    @Test
    fun healthyRunningIsNotTornDown() {
        val m = metrics(healthUnixSec = 1_000L)
        assertEquals(EngineHealth.TeardownReason.Healthy, EngineHealth.teardownReason(m, 1_002L))
        assertFalse(EngineHealth.shouldTearDown(m, 1_002L))
        assertTrue(EngineHealth.shouldConnect(m, 1_002L))
    }

    @Test
    fun failedStateReportsPumpFailure() {
        val m = metrics(state = "FAILED", egressErr = "gVisor output pump exited")
        val reason = EngineHealth.teardownReason(m, 1_002L)
        assertTrue(reason is EngineHealth.TeardownReason.PumpFailed)
        assertEquals("gVisor output pump exited", (reason as EngineHealth.TeardownReason.PumpFailed).detail)
        assertTrue(EngineHealth.shouldTearDown(m, 1_002L))
        assertTrue(
            EngineHealth.shortCause(reason).contains("engine packet pump failed")
        )
    }

    @Test
    fun runningWithoutTransportReportsTransportLost() {
        val m = metrics(transport = TransportType.UNKNOWN)
        assertEquals(
            EngineHealth.TeardownReason.TransportLost,
            EngineHealth.teardownReason(m, 1_002L)
        )
        assertTrue(EngineHealth.shouldTearDown(m, 1_002L))
        assertTrue(
            EngineHealth.shortCause(EngineHealth.TeardownReason.TransportLost).contains("transport lost")
        )
    }

    @Test
    fun staleHealthReportsAgeAndState() {
        val m = metrics(healthUnixSec = 900L)
        val reason = EngineHealth.teardownReason(m, 1_000L)
        assertTrue(reason is EngineHealth.TeardownReason.HealthStale)
        val stale = reason as EngineHealth.TeardownReason.HealthStale
        assertEquals(100L, stale.ageSec)
        assertEquals("RUNNING", stale.state)
        assertTrue(EngineHealth.shouldTearDown(m, 1_000L))
        assertTrue(EngineHealth.shortCause(reason).contains("100s"))
    }

    @Test
    fun shortCauseNeverBlank() {
        val reasons = listOf(
            EngineHealth.TeardownReason.Healthy,
            EngineHealth.TeardownReason.PumpFailed(null),
            EngineHealth.TeardownReason.PumpFailed("boom"),
            EngineHealth.TeardownReason.TransportLost,
            EngineHealth.TeardownReason.HealthStale(7L, "PREPARED")
        )
        for (reason in reasons) {
            assertTrue(EngineHealth.shortCause(reason).isNotBlank())
        }
    }
}
