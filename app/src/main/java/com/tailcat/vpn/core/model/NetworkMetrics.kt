package com.tailcat.vpn.core.model

import org.json.JSONObject

enum class TransportType {
    DIRECT_P2P,
    DERP_RELAY,
    UNKNOWN
}

data class DropCounters(
    val malformedIp: Long = 0,
    val mtuExceeded: Long = 0,
    val queueExhaustion: Long = 0,
    val policyRejections: Long = 0
)

data class NetworkMetrics(
    val version: Int = 2,
    val sessionId: Long = 0,
    val state: String = "",
    val healthUnixSec: Long = 0,
    val transportType: TransportType = TransportType.UNKNOWN,
    val directEndpoint: String? = null,
    val derpRegionId: Int? = null,
    val derpRegionCode: String? = null,
    val derpRegionName: String? = null,
    val tunnelEgressIp: String? = null,
    val rttLatencyMs: Long = 0,
    val jitterMs: Long? = null,
    val lastHandshakeSec: Long = 0,
    val wireguardTxBytes: Long = 0,
    val wireguardRxBytes: Long = 0,
    val tunTxBytes: Long = 0,
    val tunRxBytes: Long = 0,
    val txBytes: Long = 0,
    val rxBytes: Long = 0,
    val txRateKbps: Long = 0,
    val rxRateKbps: Long = 0,
    val tcpPackets: Long = 0,
    val udpPackets: Long = 0,
    val dnsQueries: Long = 0,
    val dropCounters: DropCounters = DropCounters(),
    val egressAuditTimestampSec: Long = 0,
    val egressAuditError: String? = null
) {
    fun isLiveRunning(nowUnixSec: Long, maxAgeSec: Long = 5): Boolean {
        if (state != "RUNNING") return false
        if (healthUnixSec <= 0L) return false
        return nowUnixSec - healthUnixSec <= maxAgeSec
    }

    companion object {
        fun fromJson(raw: String): NetworkMetrics {
            val json = JSONObject(raw)

            val version = json.optInt("version", 0)
            if (version != 2) {
                error("Unsupported telemetry schema version: $version")
            }

            val transport = when (json.optString("transport").uppercase()) {
                "DIRECT", "DIRECT_P2P" -> TransportType.DIRECT_P2P
                "DERP", "DERP_RELAY" -> TransportType.DERP_RELAY
                else -> TransportType.UNKNOWN
            }

            val dropJson = json.optJSONObject("dropCounters")
            val dropCounters = if (dropJson != null) {
                DropCounters(
                    malformedIp = dropJson.optLong("malformedIp", 0L),
                    mtuExceeded = dropJson.optLong("mtuExceeded", 0L),
                    queueExhaustion = dropJson.optLong("queueExhaustion", 0L),
                    policyRejections = dropJson.optLong("policyRejections", 0L)
                )
            } else {
                DropCounters()
            }

            val jitter = if (json.isNull("jitterMs") || !json.has("jitterMs")) {
                null
            } else {
                json.optLong("jitterMs")
            }

            val derpId = if (json.isNull("derpRegionId") || !json.has("derpRegionId")) null else json.optInt("derpRegionId")

            fun optNullableString(key: String): String? {
                if (json.isNull(key)) return null
                val v = json.optString(key, "")
                return v.ifBlank { null }
            }

            val state = if (!json.has("state") || json.isNull("state")) {
                ""
            } else {
                json.optString("state", "").ifBlank { "" }
            }

            return NetworkMetrics(
                version = version,
                sessionId = json.optLong("sessionId", 0L),
                state = state,
                healthUnixSec = json.optLong("healthUnixSec", 0L),
                transportType = transport,
                directEndpoint = optNullableString("directEndpoint"),
                derpRegionId = derpId,
                derpRegionCode = optNullableString("derpRegionCode"),
                derpRegionName = optNullableString("derpRegionName"),
                tunnelEgressIp = optNullableString("tunnelEgressIp"),
                rttLatencyMs = json.optLong("rttMs", 0L).coerceAtLeast(0L),
                jitterMs = jitter,
                lastHandshakeSec = json.optLong("lastHandshakeSec", 0L),
                wireguardTxBytes = json.optLong("wireguardTxBytes", 0L),
                wireguardRxBytes = json.optLong("wireguardRxBytes", 0L),
                tunTxBytes = json.optLong("tunTxBytes", 0L),
                tunRxBytes = json.optLong("tunRxBytes", 0L),
                txBytes = json.optLong("txBytes", 0L).coerceAtLeast(0L),
                rxBytes = json.optLong("rxBytes", 0L).coerceAtLeast(0L),
                txRateKbps = json.optLong("txRateKbps", 0L).coerceAtLeast(0L),
                rxRateKbps = json.optLong("rxRateKbps", 0L).coerceAtLeast(0L),
                tcpPackets = json.optLong("tcpPackets", 0L),
                udpPackets = json.optLong("udpPackets", 0L),
                dnsQueries = json.optLong("dnsQueries", 0L),
                dropCounters = dropCounters,
                egressAuditTimestampSec = json.optLong("egressAuditTimestampSec", 0L),
                egressAuditError = optNullableString("egressAuditError")
            )
        }
    }
}
