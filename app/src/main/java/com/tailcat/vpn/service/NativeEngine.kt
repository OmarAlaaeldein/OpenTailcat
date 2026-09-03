package com.tailcat.vpn.service

import com.tailcat.vpn.core.model.NetworkMetrics

interface NativeEngine {
    val availability: EngineAvailability
    fun prepare(token: String)
    fun attachTun(tunFd: Int)
    fun detachTun()
    fun stop()
    fun getStats(): NetworkMetrics
    fun updateNetworkState(networkStateJson: String)
}
