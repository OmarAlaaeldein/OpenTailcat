package com.tailcat.vpn

import com.tailcat.vpn.core.NetworkMonitor
import com.tailcat.vpn.core.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkMonitorTest {
    private data class Candidate(
        val name: String,
        val transport: NetworkType,
        val validated: Boolean = true
    )

    private val wifi = Candidate("wlan0", NetworkType.WIFI)
    private val cell = Candidate("rmnet_data0", NetworkType.CELLULAR)
    private val ethernet = Candidate("eth0", NetworkType.ETHERNET)
    // A VPN can advertise its underlay's transport; it remains excluded from candidates.
    private val vpn = Candidate("tun0", NetworkType.WIFI)

    private fun select(
        candidates: List<Candidate>,
        default: Candidate? = null,
        best: Candidate? = null,
        reportedTransport: NetworkType = NetworkType.NONE
    ): Candidate? = NetworkMonitor.selectUnderlyingNetwork(
        candidates, default, best, reportedTransport
    ) { it.transport }

    @Test
    fun platformDefaultWinsOverTransportRankingAndCandidateOrder() {
        assertEquals(cell, select(listOf(wifi, ethernet, cell), default = cell))
        assertEquals(cell, select(listOf(cell, ethernet, wifi), default = cell))
    }

    @Test
    fun applicationDefaultWinsOverBestMatchingHint() {
        assertEquals(
            cell,
            select(listOf(wifi, cell), default = cell, best = wifi, reportedTransport = NetworkType.WIFI)
        )
    }

    @Test
    fun unvalidatedPlatformDefaultIsNotReplacedByValidatedBackgroundNetwork() {
        val captiveWifi = wifi.copy(validated = false)
        assertEquals(
            captiveWifi,
            select(listOf(cell, captiveWifi), default = captiveWifi, best = cell)
        )
    }

    @Test
    fun vpnApplicationDefaultUsesPlatformsBestNonVpnNetwork() {
        assertEquals(cell, select(listOf(wifi, cell), default = vpn, best = cell))
        assertEquals(wifi, select(listOf(wifi, cell), default = vpn, best = wifi))
    }

    @Test
    fun bestMatchingNetworkDoesNotRequireAnApplicationDefault() {
        assertEquals(cell, select(listOf(wifi, cell), best = cell))
    }

    @Test
    fun bestMatchingChoiceWinsOverLegacyTransportHint() {
        assertEquals(
            cell,
            select(listOf(wifi, cell), default = vpn, best = cell, reportedTransport = NetworkType.WIFI)
        )
    }

    @Test
    fun legacyPlatformTransportSelectsOnlyTheMatchingCandidate() {
        assertEquals(
            cell,
            select(listOf(ethernet, wifi, cell), default = vpn, reportedTransport = NetworkType.CELLULAR)
        )
    }

    @Test
    fun legacyTransportDoesNotChooseArbitrarilyBetweenNetworksOfTheSameType() {
        val secondCell = cell.copy(name = "rmnet_data1")
        assertNull(
            select(listOf(cell, wifi, secondCell), default = vpn, reportedTransport = NetworkType.CELLULAR)
        )
    }

    @Test
    fun missingPlatformTransportMatchDoesNotSelectAnotherTransport() {
        assertNull(select(listOf(wifi), default = vpn, reportedTransport = NetworkType.CELLULAR))
    }

    @Test
    fun unknownVpnUnderlayDoesNotUseTransportValidationOrListRankings() {
        val unvalidatedWifi = wifi.copy(validated = false)
        assertNull(select(listOf(unvalidatedWifi, cell), default = vpn))
        assertNull(select(listOf(cell, unvalidatedWifi), default = vpn))
        assertNull(select(listOf(wifi, ethernet, cell), default = vpn))
    }

    @Test
    fun soleVisibleUnderlayDoesNotNeedARanking() {
        assertEquals(cell, select(listOf(cell), default = vpn))
    }

    @Test
    fun departedPlatformChoicesAreNotRetained() {
        assertNull(select(listOf(wifi, ethernet), default = cell, best = cell))
        assertEquals(wifi, select(listOf(wifi), default = cell, best = cell))
    }

    @Test
    fun offlineDoesNotReuseThePreviousNetworkOrVpn() {
        assertNull(select(emptyList(), default = cell, best = cell))
        assertNull(select(emptyList(), default = vpn))
    }
}
