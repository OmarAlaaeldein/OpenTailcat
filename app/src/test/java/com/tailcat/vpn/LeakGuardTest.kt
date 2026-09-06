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
        assertEquals(
            LeakGuard.SPLIT_TUNNEL_BLOCKED,
            LeakGuard.refusalReasonForStartup(
                sdkInt = 30,
                settingsLockdown = true,
                frameworkLockdownEnabled = true,
                splitTunnelEmpty = false
            )
        )
    }

    @Test
    fun testApi26AllowsWithoutLockdownQuery() {
        assertTrue(LeakGuard.mayInstallDefaultRoutes(26, lockdownEnabled = false, splitTunnelEmpty = true))
        assertNull(
            LeakGuard.refusalReasonForStartup(
                sdkInt = 26,
                settingsLockdown = false,
                frameworkLockdownEnabled = false,
                splitTunnelEmpty = true
            )
        )
    }

    @Test
    fun testStartupCombinesSettingsAndFramework() {
        assertNull(
            LeakGuard.refusalReasonForStartup(
                sdkInt = 30,
                settingsLockdown = true,
                frameworkLockdownEnabled = false,
                splitTunnelEmpty = true
            )
        )
        assertEquals(
            LeakGuard.LOCKDOWN_REQUIRED,
            LeakGuard.refusalReasonForStartup(
                sdkInt = 30,
                settingsLockdown = null,
                frameworkLockdownEnabled = false,
                splitTunnelEmpty = true
            )
        )
    }
}
