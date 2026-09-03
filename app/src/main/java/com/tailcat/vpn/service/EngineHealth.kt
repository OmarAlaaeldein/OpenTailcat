package com.tailcat.vpn.service

import com.tailcat.vpn.core.model.NetworkMetrics
import com.tailcat.vpn.core.model.TransportType

object EngineHealth {
    fun shouldConnect(metrics: NetworkMetrics, nowUnixSec: Long): Boolean {
        if (metrics.transportType == TransportType.UNKNOWN) return false
        return metrics.isLiveRunning(nowUnixSec)
    }

    fun shouldTearDown(metrics: NetworkMetrics, nowUnixSec: Long): Boolean {
        if (metrics.state == "FAILED") return true
        return !metrics.isLiveRunning(nowUnixSec)
    }
}
