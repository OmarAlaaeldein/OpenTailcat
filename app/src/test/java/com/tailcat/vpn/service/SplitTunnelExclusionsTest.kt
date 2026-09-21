package com.tailcat.vpn.service

import org.junit.Assert.assertEquals
import org.junit.Test

class SplitTunnelExclusionsTest {

    private val installed = setOf("com.alpha", "com.beta", "com.gamma")

    private fun isInstalled(pkg: String) = pkg in installed

    @Test
    fun skipsBlankAndUninstalledEntries() {
        assertEquals(
            listOf("com.alpha", "com.beta"),
            SplitTunnelExclusions.validPackages(
                excluded = setOf("com.beta", "", "  ", "com.alpha", "com.uninstalled"),
                isInstalled = ::isInstalled
            )
        )
    }

    @Test
    fun returnsInstalledEntriesInDeterministicOrder() {
        assertEquals(
            listOf("com.alpha", "com.beta", "com.gamma"),
            SplitTunnelExclusions.validPackages(
                excluded = setOf("com.gamma", "com.alpha", "com.beta"),
                isInstalled = ::isInstalled
            )
        )
    }

    @Test
    fun emptySelectionStaysEmpty() {
        assertEquals(
            emptyList<String>(),
            SplitTunnelExclusions.validPackages(
                excluded = emptySet(),
                isInstalled = ::isInstalled
            )
        )
    }

    @Test
    fun everythingUninstalledStaysEmpty() {
        assertEquals(
            emptyList<String>(),
            SplitTunnelExclusions.validPackages(
                excluded = setOf("com.gone.one", "com.gone.two"),
                isInstalled = ::isInstalled
            )
        )
    }
}
