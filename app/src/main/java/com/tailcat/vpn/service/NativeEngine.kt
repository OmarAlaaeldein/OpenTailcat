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
    fun setSocketProtector(protect: ((Int) -> Boolean)?)
    /**
     * Installs the destination ranges the tunnel refuses (whitespace-separated
     * CIDR prefixes; empty clears the filter). The engine reads it when the TUN
     * attaches, so call it before every start. It fails, and the start must be
     * abandoned, when the list is invalid or blocks [resolverIp], the DNS
     * resolver the tunnel will advertise.
     */
    fun setCountryBlocklist(prefixes: String, resolverIp: String)
    fun ensureTransportProtect()
    fun measureTunnelPingMs(): Long
    /** [onProgress] receives the running Mbps and the elapsed share (0..1) of the test window. */
    fun measureTunnelDownloadMbps(onProgress: (mbps: Double, fraction: Float) -> Unit): Double
    fun measureTunnelUploadMbps(onProgress: (mbps: Double, fraction: Float) -> Unit): Double
}
