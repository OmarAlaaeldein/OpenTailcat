package com.tailcat.vpn.core.model

enum class TransportType {
    DIRECT_P2P,
    DERP_RELAY,
    UNKNOWN
}

data class NetworkMetrics(
    val transportType: TransportType = TransportType.UNKNOWN,
    val derpRegionId: Int? = null,
    val derpRegionName: String? = null,
	val tunnelEgressIp: String? = null,
    val rttLatencyMs: Long = 0,
    val jitterMs: Long = 0,
    val txBytes: Long = 0,
    val rxBytes: Long = 0,
    val txRateKbps: Long = 0,
    val rxRateKbps: Long = 0
)
