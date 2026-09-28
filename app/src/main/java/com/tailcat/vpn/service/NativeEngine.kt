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
    fun ensureTransportProtect()
    fun measureTunnelPingMs(): Long
    /** [onProgress] receives the running Mbps and the elapsed share (0..1) of the test window. */
    fun measureTunnelDownloadMbps(onProgress: (mbps: Double, fraction: Float) -> Unit): Double
    fun measureTunnelUploadMbps(onProgress: (mbps: Double, fraction: Float) -> Unit): Double
}
