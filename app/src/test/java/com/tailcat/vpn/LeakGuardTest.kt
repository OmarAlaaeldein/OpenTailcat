package com.tailcat.vpn

import com.tailcat.vpn.service.LeakGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LeakGuardTest {
    @Test
    fun testApi29RequiresLockdown() {
        assertFalse(LeakGuard.mayInstallDefaultRoutes(29, lockdownEnabled = false, splitTunnelEmpty = true))
        assertEquals(
            LeakGuard.LOCKDOWN_REQUIRED,
            LeakGuard.refusalReason(29, lockdownEnabled = false, splitTunnelEmpty = true)
        )
        assertTrue(LeakGuard.mayInstallDefaultRoutes(29, lockdownEnabled = true, splitTunnelEmpty = true))
        assertNull(LeakGuard.refusalReason(29, lockdownEnabled = true, splitTunnelEmpty = true))
    }

    @Test
    fun testSplitTunnelAlwaysBlocked() {
        assertFalse(LeakGuard.mayInstallDefaultRoutes(29, lockdownEnabled = true, splitTunnelEmpty = false))
        assertEquals(
            LeakGuard.SPLIT_TUNNEL_BLOCKED,
            LeakGuard.refusalReason(26, lockdownEnabled = false, splitTunnelEmpty = false)
        )
    }

    @Test
    fun testApi26AllowsWithoutLockdownQuery() {
        assertTrue(LeakGuard.mayInstallDefaultRoutes(26, lockdownEnabled = false, splitTunnelEmpty = true))
    }
}