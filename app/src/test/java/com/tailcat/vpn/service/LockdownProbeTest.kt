package com.tailcat.vpn.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockdownProbeTest {

    @Test
    fun unknownUnlessTheServiceReportedIt() {
        assertTrue(LockdownProbe.statusText(35, null).contains("only while the VPN is connected"))
        assertTrue(LockdownProbe.statusText(28, AlwaysOnStatus(true, true)).contains("Android 8–9"))
        assertFalse(LockdownProbe.isProtected(null))
    }

    @Test
    fun reportsEachCombination() {
        assertTrue(LockdownProbe.statusText(35, AlwaysOnStatus(alwaysOn = true, lockdown = true)).contains("is ON"))
        assertTrue(LockdownProbe.statusText(35, AlwaysOnStatus(alwaysOn = true, lockdown = false)).contains("is off"))
        assertTrue(LockdownProbe.statusText(35, AlwaysOnStatus(alwaysOn = false, lockdown = false)).contains("Always-on VPN is off"))
        assertTrue(LockdownProbe.isProtected(AlwaysOnStatus(alwaysOn = true, lockdown = true)))
        assertFalse(LockdownProbe.isProtected(AlwaysOnStatus(alwaysOn = true, lockdown = false)))
    }
}
