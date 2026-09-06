package com.tailcat.vpn.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LockdownProbeTest {
    private val pkg = "com.tailcat.vpn"

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
        assertEquals(
            LeakGuard.LOCKDOWN_REQUIRED,
            LeakGuard.refusalReasonForStartup(
                sdkInt = 29,
                settingsLockdown = false,
                frameworkLockdownEnabled = false,
                splitTunnelEmpty = true
            )
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

    @Test
    fun settingsConfiguredWhenAppAndLockdownMatch() {
        assertEquals(
            true,
            LockdownProbe.alwaysOnLockdownConfigured(
                resolver = null,
                packageName = pkg,
                sdkInt = 30,
                readString = { _, key ->
                    if (key == LockdownProbe.SECURE_ALWAYS_ON_VPN_APP) pkg else null
                },
                readInt = { _, key, def ->
                    if (key == LockdownProbe.SECURE_ALWAYS_ON_VPN_LOCKDOWN) 1 else def
                }
            )
        )
    }

    @Test
    fun settingsFalseWhenLockdownOffOrOtherApp() {
        assertEquals(
            false,
            LockdownProbe.alwaysOnLockdownConfigured(
                resolver = null,
                packageName = pkg,
                sdkInt = 30,
                readString = { _, _ -> pkg },
                readInt = { _, _, _ -> 0 }
            )
        )
        assertEquals(
            false,
            LockdownProbe.alwaysOnLockdownConfigured(
                resolver = null,
                packageName = pkg,
                sdkInt = 30,
                readString = { _, _ -> "com.other.vpn" },
                readInt = { _, _, _ -> 1 }
            )
        )
    }

    @Test
    fun settingsUnreadableBecomesNull() {
        assertNull(
            LockdownProbe.alwaysOnLockdownConfigured(
                resolver = null,
                packageName = pkg,
                sdkInt = 30,
                readString = { _, _ -> throw SecurityException("denied") },
                readInt = { _, _, def -> def }
            )
        )
    }

    @Test
    fun settingsFalseButFrameworkTrueAllowsRoutes() {
        assertTrue(
            LockdownProbe.lockdownSatisfied(
                sdkInt = 30,
                settingsLockdown = false,
                frameworkLockdownEnabled = true
            )
        )
    }
}
