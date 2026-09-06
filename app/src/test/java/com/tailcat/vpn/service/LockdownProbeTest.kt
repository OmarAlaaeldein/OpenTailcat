package com.tailcat.vpn.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LockdownProbeTest {
    @Test
    fun settingsLockdownSatisfiesEvenIfFrameworkFalse() {
        assertTrue(
            LockdownProbe.lockdownSatisfied(
                sdkInt = 29,
                settingsLockdown = true,
                frameworkLockdownEnabled = false
            )
        )
        assertNull(
            LeakGuard.refusalReasonForStartup(
                sdkInt = 29,
                settingsLockdown = true,
                frameworkLockdownEnabled = false,
                splitTunnelEmpty = true
            )
        )
    }

    @Test
    fun frameworkAloneSatisfiesWhenSettingsUnknown() {
        assertTrue(
            LockdownProbe.lockdownSatisfied(
                sdkInt = 29,
                settingsLockdown = null,
                frameworkLockdownEnabled = true
            )
        )
    }

    @Test
    fun neitherSignalRefusesOnApi29() {
        assertFalse(
            LockdownProbe.lockdownSatisfied(
                sdkInt = 29,
                settingsLockdown = false,
                frameworkLockdownEnabled = false
            )
        )
        assertTrue(
            LeakGuard.refusalReasonForStartup(
                sdkInt = 29,
                settingsLockdown = false,
                frameworkLockdownEnabled = false,
                splitTunnelEmpty = true
            ) == LeakGuard.LOCKDOWN_REQUIRED
        )
    }

    @Test
    fun belowApi29AlwaysSatisfied() {
        assertTrue(
            LockdownProbe.lockdownSatisfied(
                sdkInt = 28,
                settingsLockdown = false,
                frameworkLockdownEnabled = false
            )
        )
    }
}
