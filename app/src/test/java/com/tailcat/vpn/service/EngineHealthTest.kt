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

    @Test
    fun sixSecondGapStillLiveUnderDefaultWindow() {
        // Regression: banner "no fresh engine health for 6s" used to tear down
        // because maxAgeSec was 5. Transport still live => must remain Healthy.
        val m = metrics(healthUnixSec = 1_000L)
        assertEquals(EngineHealth.TeardownReason.Healthy, EngineHealth.teardownReason(m, 1_006L))
        assertTrue(EngineHealth.shouldConnect(m, 1_006L))
        assertFalse(EngineHealth.shouldTearDown(m, 1_006L))
    }

    @Test
    fun healthBeyondWindowIsStaleButNeedsConsecutivePolls() {
        val m = metrics(healthUnixSec = 1_000L)
        val now = 1_000L + com.tailcat.vpn.core.model.NetworkMetrics.DEFAULT_HEALTH_MAX_AGE_SEC + 1L
        val reason = EngineHealth.teardownReason(m, now)
        assertTrue(reason is EngineHealth.TeardownReason.HealthStale)
        assertFalse(EngineHealth.stalePollsRequireTeardown(1))
        assertFalse(EngineHealth.stalePollsRequireTeardown(2))
        assertTrue(EngineHealth.stalePollsRequireTeardown(3))
        assertTrue(EngineHealth.stalePollsRequireTeardown(EngineHealth.STALE_TEARDOWN_POLLS))
    }

    @Test
    fun nextStalePollCountIncrementsOnlyForHealthStale() {
        assertEquals(1, EngineHealth.nextStalePollCount(EngineHealth.TeardownReason.HealthStale(6L, "RUNNING"), 0))
        assertEquals(3, EngineHealth.nextStalePollCount(EngineHealth.TeardownReason.HealthStale(6L, "RUNNING"), 2))
        assertEquals(0, EngineHealth.nextStalePollCount(EngineHealth.TeardownReason.Healthy, 2))
        assertEquals(0, EngineHealth.nextStalePollCount(EngineHealth.TeardownReason.TransportLost, 2))
        assertEquals(0, EngineHealth.nextStalePollCount(EngineHealth.TeardownReason.PumpFailed("x"), 2))
    }

    @Test
    fun pumpFailureStillImmediateRegardlessOfStaleCounter() {
        val m = metrics(state = "FAILED", egressErr = "gVisor output pump exited")
        val reason = EngineHealth.teardownReason(m, 1_002L)
        assertTrue(reason is EngineHealth.TeardownReason.PumpFailed)
        // Immediate path: caller tears down without waiting for stale polls.
        assertTrue(EngineHealth.shouldTearDown(m, 1_002L))
    }
}
