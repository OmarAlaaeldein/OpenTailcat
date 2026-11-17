package com.tailcat.vpn.service

import com.tailcat.vpn.core.exitnode.ExitNodeStats
import com.tailcat.vpn.core.model.TunnelState
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExitNodeControllerTest {

    private class FakeExitNode(
        override var availability: EngineAvailability = EngineAvailability(true, "ok")
    ) : NativeExitNode {
        var state = "RUNNING"
        var expires = NOW + 3600
        var startError: String? = null
        var statsError = false
        var starts = 0
        var stops = 0
        val approved = mutableListOf<String>()
        val denied = mutableListOf<String>()
        val revoked = mutableListOf<String>()
        var token = "tcFAKE"

        override fun start(config: ExitNodeConfig): String {
            starts++
            startError?.let { throw IllegalStateException(it) }
            return token
        }

        override fun statsJson(): String {
            if (statsError) throw IllegalStateException("engine gone")
            return JSONObject()
                .put("schema", 1).put("state", state).put("admission", "approval")
                .put("startedUnixSec", NOW).put("expiresUnixSec", expires)
                .put("activeTcp", 0).put("activeUdp", 0).put("totalTcp", 0).put("totalUdp", 0)
                .put("bytesFromGuests", 0).put("bytesToGuests", 0)
                .put("policyRejections", 0).put("capRejections", 0).put("dialFailures", 0)
                .put("pending", JSONArray()).put("guests", JSONArray())
                .toString()
        }

        override fun approve(deviceKey: String) { approved += deviceKey }
        override fun deny(deviceKey: String) { denied += deviceKey }
        override fun revoke(deviceKey: String): Boolean { revoked += deviceKey; return true }
        override fun stop() { stops++ }
    }

    private var tunnel = TunnelState.DISCONNECTED
    private var now = NOW
    private val engine = FakeExitNode()
    private val controller = ExitNodeController(engine, { tunnel }, { now })
    private val config = ExitNodeConfig(ttlSeconds = 3600)

    private fun sharing() = controller.state.value as ExitNodeUiState.Sharing

    // ---- the interlock ----

    @Test
    fun refusesToStartWhileTheVpnIsUp() {
        for (state in TunnelState.entries.filter { it != TunnelState.DISCONNECTED }) {
            tunnel = state
            val refusal = controller.start(config)
            assertNotNull("started while VPN was $state", refusal)
            assertEquals(ExitNodeUiState.Idle, controller.state.value)
        }
        assertEquals("the engine must not be touched on a refusal", 0, engine.starts)
    }

    @Test
    fun theVpnIsBlockedWhileSharing() {
        assertNull("nothing shared yet", controller.startBlockReason())
        assertNull(controller.start(config))
        assertNotNull(controller.startBlockReason())
        controller.stop()
        assertNull("a stopped session releases the VPN", controller.startBlockReason())
    }

    @Test
    fun aRaceThatLetsBothRunEndsSharing() {
        assertNull(controller.start(config))
        tunnel = TunnelState.CONNECTING // the VPN got in anyway
        controller.refresh()

        val ended = controller.state.value as ExitNodeUiState.Ended
        assertEquals(ExitNodeEnd.STOPPED, ended.reason)
        assertEquals("the native session must be torn down", 1, engine.stops)
    }

    // ---- start ----

    @Test
    fun startPublishesTheTokenAndStats() {
        assertNull(controller.start(config))
        assertEquals("tcFAKE", sharing().token)
        assertTrue(sharing().stats.isRunning)
        assertTrue(controller.isActive)
    }

    @Test
    fun aSecondStartIsRefused() {
        assertNull(controller.start(config))
        assertNotNull(controller.start(config))
        assertEquals(1, engine.starts)
    }

    @Test
    fun anUnavailableEngineIsReportedNotAttempted() {
        val unavailable = ExitNodeController(UnavailableExitNode, { tunnel })
        val refusal = unavailable.start(config)
        assertEquals(UnavailableExitNode.availability.message, refusal)
        assertEquals(ExitNodeUiState.Idle, unavailable.state.value)
    }

    @Test
    fun aFailedStartEndsFailedAndCleansUp() {
        engine.startError = "no relay reachable"
        assertEquals("no relay reachable", controller.start(config))
        val ended = controller.state.value as ExitNodeUiState.Ended
        assertEquals(ExitNodeEnd.FAILED, ended.reason)
        assertFalse(controller.isActive)
        assertTrue("a half-started engine must be stopped", engine.stops >= 1)
        // And it is startable again after acknowledging.
        controller.acknowledgeEnded()
        engine.startError = null
        assertNull(controller.start(config))
    }

    // ---- polling ----

    @Test
    fun expiryEndsSharingEvenIfTheEngineHasNotSaidSo() {
        assertNull(controller.start(config))
        now = engine.expires + 1
        controller.refresh()
        val ended = controller.state.value as ExitNodeUiState.Ended
        assertEquals(ExitNodeEnd.EXPIRED, ended.reason)
        assertEquals(1, engine.stops)
    }

    @Test
    fun anEngineThatStoppedItselfEndsSharing() {
        assertNull(controller.start(config))
        engine.state = ExitNodeStats.STATE_EXPIRED
        controller.refresh()
        assertEquals(ExitNodeEnd.EXPIRED, (controller.state.value as ExitNodeUiState.Ended).reason)
    }

    @Test
    fun statsFailuresAreToleratedThenEndSharing() {
        assertNull(controller.start(config))
        val before = sharing().stats
        engine.statsError = true

        controller.refresh()
        controller.refresh()
        assertEquals("two misses keep the last known good view", before, sharing().stats)

        controller.refresh()
        val ended = controller.state.value as ExitNodeUiState.Ended
        assertEquals(ExitNodeEnd.FAILED, ended.reason)
        assertEquals(1, engine.stops)
    }

    @Test
    fun aSuccessfulPollResetsTheFailureCount() {
        assertNull(controller.start(config))
        engine.statsError = true
        controller.refresh(); controller.refresh()
        engine.statsError = false
        controller.refresh()
        engine.statsError = true
        controller.refresh(); controller.refresh()
        assertTrue("two more misses after a success must not end the session", controller.isActive)
    }

    @Test
    fun refreshWhenIdleDoesNothing() {
        controller.refresh()
        assertEquals(ExitNodeUiState.Idle, controller.state.value)
        assertEquals(0, engine.stops)
    }

    // ---- stop and device actions ----

    @Test
    fun stopIsIdempotentAndAlwaysReachesTheEngine() {
        assertNull(controller.start(config))
        controller.stop()
        controller.stop()
        assertEquals(ExitNodeEnd.STOPPED, (controller.state.value as ExitNodeUiState.Ended).reason)
        assertTrue(engine.stops >= 2)
    }

    @Test
    fun stopWhenIdleStillStopsTheEngine() {
        controller.stop()
        assertEquals(ExitNodeUiState.Idle, controller.state.value)
        assertEquals("a stuck native session must be stoppable from Idle", 1, engine.stops)
    }

    @Test
    fun deviceActionsReachTheEngineOnlyWhileSharing() {
        assertNotNull(controller.approve("nodekey:aa"))
        assertTrue(engine.approved.isEmpty())

        assertNull(controller.start(config))
        assertNull(controller.approve("nodekey:aa"))
        assertNull(controller.deny("nodekey:bb"))
        assertNull(controller.revoke("nodekey:cc"))
        assertEquals(listOf("nodekey:aa"), engine.approved)
        assertEquals(listOf("nodekey:bb"), engine.denied)
        assertEquals(listOf("nodekey:cc"), engine.revoked)
    }

    @Test
    fun aDeviceActionFailureIsReportedNotThrown() {
        val failing = object : NativeExitNode by engine {
            override fun approve(deviceKey: String) = throw IllegalStateException("session is over")
        }
        val c = ExitNodeController(failing, { tunnel }, { now })
        assertNull(c.start(config))
        assertEquals("session is over", c.approve("nodekey:aa"))
    }

    @Test
    fun aRefusalMadeBeforeStartIsShownAsAnEndedSession() {
        controller.fail("Disconnect the VPN first.")
        val ended = controller.state.value as ExitNodeUiState.Ended
        assertEquals(ExitNodeEnd.FAILED, ended.reason)
        assertEquals("Disconnect the VPN first.", ended.message)
        assertFalse("a refusal must not look like a live session", controller.isActive)
    }

    @Test
    fun failNeverOverwritesALiveSession() {
        assertNull(controller.start(config))
        controller.fail("late refusal")
        assertTrue(controller.state.value is ExitNodeUiState.Sharing)
    }

    @Test
    fun configRejectsOutOfRangeDurations() {
        for (bad in listOf(0L, -1L, ExitNodeConfig.MAX_TTL_SECONDS + 1)) {
            val threw = runCatching { ExitNodeConfig(bad) }.isFailure
            assertTrue("ttl $bad accepted", threw)
        }
        ExitNodeConfig(ExitNodeConfig.MAX_TTL_SECONDS)
    }

    private companion object {
        const val NOW = 1_800_000_000L
    }
}
