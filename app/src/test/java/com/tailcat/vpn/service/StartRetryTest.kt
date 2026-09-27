package com.tailcat.vpn.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartRetryTest {

    @Test
    fun backsOffToCap() {
        assertEquals(2_000L, StartRetry.delayMs(1))
        assertEquals(4_000L, StartRetry.delayMs(2))
        assertEquals(8_000L, StartRetry.delayMs(3))
        assertEquals(16_000L, StartRetry.delayMs(4))
        assertEquals(StartRetry.MAX_DELAY_MS, StartRetry.delayMs(5))
        assertEquals(StartRetry.MAX_DELAY_MS, StartRetry.delayMs(10_000))
        assertEquals(2_000L, StartRetry.delayMs(0))
    }

    @Test
    fun tokenAndEngineErrorsArePermanent() {
        assertTrue(StartRetry.isPermanentEngineError("token rejected: token has expired"))
        assertTrue(
            StartRetry.isPermanentEngineError(
                "token classification LEGACY cannot be used for connection: reissue"
            )
        )
        assertTrue(StartRetry.isPermanentEngineError("VPN engine is missing prepare"))
    }

    @Test
    fun networkErrorsAreRetried() {
        assertFalse(StartRetry.isPermanentEngineError(null))
        assertFalse(StartRetry.isPermanentEngineError("gateway handshake timed out"))
        assertFalse(StartRetry.isPermanentEngineError("VPN engine did not become live after attach"))
        assertFalse(StartRetry.isPermanentEngineError("dial tcp: network is unreachable"))
    }
}
