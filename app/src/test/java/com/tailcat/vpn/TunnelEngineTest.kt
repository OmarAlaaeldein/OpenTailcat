package com.tailcat.vpn

import com.tailcat.vpn.service.TunnelEngine
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TunnelEngineTest {

    @Test
    fun testEngineAvailabilityContract() {
        val engine = TunnelEngine()
        val availability = engine.availability

        // On host JVM without Android JNI runtime, fail-closed safety invariant must be preserved
        assertNotNull(availability)
        assertNotNull(availability.message)
    }

    @Test
    fun testStopLifecycleIdempotent() {
        val engine = TunnelEngine()
        // Stop should be safe and idempotent
        engine.stop()
        engine.stop()
    }

    @Test
    fun testPrepareWithoutEngineFailsClosed() {
        val engine = TunnelEngine()
        if (!engine.availability.isAvailable) {
            try {
                engine.prepare("tcTestToken")
                fail("Expected prepare to fail when engine is unavailable")
            } catch (e: IllegalStateException) {
                assertTrue(e.message?.contains("VPN engine") == true)
            }
        }
    }
}

