package com.tailcat.vpn.service

import com.tailcat.vpn.core.model.TunnelState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationThrottleTest {

    @Test
    fun stateChangesPostImmediately() {
        assertTrue(NotificationThrottle.shouldPost(TunnelState.DEGRADED, TunnelState.CONNECTED, 1_000L, 900L))
        assertTrue(NotificationThrottle.shouldPost(TunnelState.CONNECTED, null, 0L, 0L))
    }

    @Test
    fun sameStateWaitsForTheInterval() {
        assertFalse(NotificationThrottle.shouldPost(TunnelState.CONNECTED, TunnelState.CONNECTED, 4_999L, 0L))
        assertTrue(NotificationThrottle.shouldPost(TunnelState.CONNECTED, TunnelState.CONNECTED, 5_000L, 0L))
    }
}
