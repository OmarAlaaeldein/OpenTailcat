package com.tailcat.vpn.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitTunnelSharedUidTest {

    @Test
    fun packagesSharingAUidAreListedAsPeers() {
        val peers = SplitTunnelExclusions.sharedUidPeers(
            listOf(
                Triple("com.a", "App A", 10_100),
                Triple("com.b", "App B", 10_200),
                Triple("android.phone", "Phone", 1001),
                Triple("android.stk", "SIM Toolkit", 1001)
            )
        )
        assertEquals(emptyList<String>(), peers["com.a"])
        assertEquals(listOf("SIM Toolkit"), peers["android.phone"])
        assertEquals(listOf("Phone"), peers["android.stk"])
    }

    @Test
    fun systemUidsAreBelowTheFirstApplicationUid() {
        assertTrue(SplitTunnelExclusions.isSystemUid(1000))
        assertFalse(SplitTunnelExclusions.isSystemUid(10_000))
    }
}
