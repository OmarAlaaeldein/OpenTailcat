package com.tailcat.vpn.service

import com.tailcat.vpn.core.model.NetworkMetrics

interface NativeEngine {
    val availability: EngineAvailability
    fun prepare(token: String)
    fun attachTun(tunFd: Int)
    fun detachTun()
    fun disarmPumps()
    fun stop()
    fun getStats(): NetworkMetrics
    fun updateNetworkState(networkStateJson: String)
    fun setSocketProtector(protect: (Int) -> Boolean)
    fun measureTunnelPingMs(): Long
    fun measureTunnelDownloadMbps(): Double
    fun measureTunnelUploadMbps(): Double
}
