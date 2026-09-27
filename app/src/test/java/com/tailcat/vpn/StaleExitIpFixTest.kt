package com.tailcat.vpn

import com.tailcat.vpn.core.model.NetworkMetrics
import com.tailcat.vpn.core.model.TransportType
import com.tailcat.vpn.ui.screens.home.components.TelemetryDisplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression for the 200.x forced-resolver / stale Exit IP bug.
 *
 * Before the fix:
 * - TelemetryCard used `transportType != UNKNOWN` to decide “Exit IP”, so a
 *   FAILED or HealthStale tunnel with a stale `tunnelEgressIp` like
 *   `200.160.0.8` or `200.111.5.10` (previous DERP/exit) was still shown as
 *   “Exit IP: 200.x” even though the data plane was down.
 * - `pendingDNS` was not cleared on `abandonPrepare`, so a failed `prepare`
 *   with a `FORCED_RESOLVER 200.x` left that resolver for the next `AttachTun`,
 *   corrupting DNS for ~30s (udpReprobeInterval) + 15s HealthStale grace.
 *
 * After the fix:
 * - TelemetryCard uses `isLiveRunning(nowSec)` (requires RUNNING + fresh
 *   health). Stale 200.x is never shown as Exit IP when disconnected.
 * - `abandonPrepare` clears `pendingDNS` (verified in Go
 *   `TestAbandonPrepareClearsPendingDNS`).
 */
class StaleExitIpFixTest {

    private fun metrics(
        state: String = "RUNNING",
        healthUnixSec: Long = 999L,
        exitIp: String? = null,
        auditError: String? = null
    ) = NetworkMetrics(
        state = state,
        transportType = TransportType.DERP_RELAY,
        healthUnixSec = healthUnixSec,
        tunnelEgressIp = exitIp,
        egressAuditError = auditError
    )

    private val now = 1_000L

    @Test
    fun failedTunnelNeverShowsItsOldExitIp() {
        val shown = TelemetryDisplay.egress(metrics(state = "FAILED", exitIp = "200.111.5.10"), "198.51.100.7", now)
        assertEquals(TelemetryDisplay.Egress("Device IP", "198.51.100.7"), shown)
    }

    @Test
    fun staleHealthNeverShowsItsOldExitIp() {
        val shown = TelemetryDisplay.egress(metrics(healthUnixSec = 1L, exitIp = "200.160.0.8"), "198.51.100.7", now)
        assertEquals("Device IP", shown.label)
        assertFalse(shown.value.startsWith("200."))
    }

    @Test
    fun liveTunnelShowsAuditedExitIp() {
        val shown = TelemetryDisplay.egress(metrics(exitIp = "203.0.113.9"), "198.51.100.7", now)
        assertEquals(TelemetryDisplay.Egress("Exit IP", "203.0.113.9"), shown)
    }

    @Test
    fun failedAuditSaysUnavailableInsteadOfCheckingForever() {
        assertEquals("Checking…", TelemetryDisplay.egress(metrics(), "x", now).value)
        val shown = TelemetryDisplay.egress(metrics(auditError = "tls: no pinned key in the verified chain"), "x", now)
        assertTrue(shown.value.startsWith("unavailable (tls: no pinned key"))
    }
}
