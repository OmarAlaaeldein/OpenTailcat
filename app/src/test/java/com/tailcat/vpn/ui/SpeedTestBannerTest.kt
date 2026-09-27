package com.tailcat.vpn.ui

import com.tailcat.vpn.core.model.TunnelState
import com.tailcat.vpn.core.speedtest.SpeedTestStage
import com.tailcat.vpn.ui.screens.speedtest.SpeedTestBanner
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedTestBannerTest {

    @Test
    fun idleScreenFollowsTheLiveTunnelState() {
        // The banner used to say "not CONNECTED" on a connected phone before a run.
        assertEquals(SpeedTestBanner.GATEWAY, SpeedTestBanner.text(SpeedTestStage.IDLE, false, TunnelState.CONNECTED))
        assertEquals(SpeedTestBanner.DEVICE, SpeedTestBanner.text(SpeedTestStage.IDLE, false, TunnelState.DISCONNECTED))
        assertEquals(SpeedTestBanner.HALF_UP, SpeedTestBanner.text(SpeedTestStage.IDLE, false, TunnelState.DEGRADED))
    }

    @Test
    fun aRunReportsThePathItUsed() {
        assertEquals(SpeedTestBanner.GATEWAY, SpeedTestBanner.text(SpeedTestStage.COMPLETED, true, TunnelState.DISCONNECTED))
        assertEquals(SpeedTestBanner.HALF_UP, SpeedTestBanner.text(SpeedTestStage.COMPLETED, false, TunnelState.RECONNECTING))
        assertEquals(SpeedTestBanner.DEVICE, SpeedTestBanner.text(SpeedTestStage.COMPLETED, false, TunnelState.DISCONNECTED))
    }
}
