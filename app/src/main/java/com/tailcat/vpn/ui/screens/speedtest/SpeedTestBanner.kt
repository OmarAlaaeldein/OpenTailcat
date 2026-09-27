package com.tailcat.vpn.ui.screens.speedtest

import com.tailcat.vpn.core.model.TunnelState
import com.tailcat.vpn.core.speedtest.SpeedTestStage

/** Which path the speed test measures, stated before and after a run. */
object SpeedTestBanner {
    const val GATEWAY =
        "Gateway tunnel benchmark: traffic uses Tailcat DialTCP through the connected gateway."
    const val DEVICE =
        "Device-route benchmark: the tunnel is off, so this measures the device's own network path."
    const val HALF_UP =
        "The tunnel is not CONNECTED: this run uses the device routes, which go into the VPN interface while it is up. It is not a gateway measurement."

    fun text(stage: SpeedTestStage, viaGateway: Boolean, tunnelState: TunnelState): String = when {
        // A started or finished run reports the path it actually used.
        stage != SpeedTestStage.IDLE && viaGateway -> GATEWAY
        stage == SpeedTestStage.IDLE && tunnelState == TunnelState.CONNECTED -> GATEWAY
        tunnelState == TunnelState.DISCONNECTED -> DEVICE
        else -> HALF_UP
    }
}
