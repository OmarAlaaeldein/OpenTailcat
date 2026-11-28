package com.tailcat.vpn.ui.screens.speedtest

import com.tailcat.vpn.core.model.TunnelState
import com.tailcat.vpn.core.speedtest.SpeedTestStage

/** Which path the speed test measures, stated before and after a run. */
object SpeedTestBanner {
    const val GATEWAY =
        "Gateway benchmark: traffic is measured through the connected gateway."
    const val DEVICE =
        "Device benchmark: the tunnel is off, so this measures the device's own network path."
    const val HALF_UP =
        "The tunnel is not connected. This run uses device routes, which enter the VPN interface while it is active; it is not a gateway measurement."

    fun text(stage: SpeedTestStage, viaGateway: Boolean, tunnelState: TunnelState): String = when {
        // A started or finished run reports the path it actually used.
        stage != SpeedTestStage.IDLE && viaGateway -> GATEWAY
        stage == SpeedTestStage.IDLE && tunnelState == TunnelState.CONNECTED -> GATEWAY
        tunnelState == TunnelState.DISCONNECTED -> DEVICE
        else -> HALF_UP
    }
}
