package com.tailcat.vpn.core.exitnode

import org.json.JSONArray
import org.json.JSONObject

/** A device that has asked to use this phone and is waiting for the host. */
data class PendingDevice(
    val key: String,
    val firstSeenUnixSec: Long,
    val lastSeenUnixSec: Long
)

/** A device the host has approved (or, in open mode, that connected). */
data class GuestDevice(
    val key: String,
    val approved: Boolean,
    val firstSeenUnixSec: Long,
    val lastActiveUnixSec: Long,
    val activeTcp: Long,
    val activeUdp: Long,
    val flows: Long,
    val bytesFromGuest: Long,
    val bytesToGuest: Long
) {
    val activeFlows: Long get() = activeTcp + activeUdp
    val totalBytes: Long get() = bytesFromGuest + bytesToGuest
}

/**
 * Point-in-time view of a running exit node, parsed from the engine's
 * `exitNodeStatsJSON`. Every value is counted natively from real flows; this
 * class never derives or estimates one.
 */
data class ExitNodeStats(
    val state: String,
    val openAdmission: Boolean,
    val startedUnixSec: Long,
    /** Null when the session has no time limit (the engine sends 0). */
    val expiresUnixSec: Long?,
    val activeTcp: Long,
    val activeUdp: Long,
    val totalTcp: Long,
    val totalUdp: Long,
    val bytesFromGuests: Long,
    val bytesToGuests: Long,
    val policyRejections: Long,
    val capRejections: Long,
    val dialFailures: Long,
    val pending: List<PendingDevice>,
    val guests: List<GuestDevice>
) {
    val isRunning: Boolean get() = state == STATE_RUNNING
    val isExpired: Boolean get() = state == STATE_EXPIRED
    val activeFlows: Long get() = activeTcp + activeUdp
    val totalBytes: Long get() = bytesFromGuests + bytesToGuests

    /**
     * More than one device asking at once is the signature of a leaked code:
     * the host handed it to one person, and someone else is trying it too.
     */
    val suspiciousRequests: Boolean get() = pending.size > 1

    companion object {
        const val SCHEMA = 1
        const val STATE_RUNNING = "RUNNING"
        const val STATE_EXPIRED = "EXPIRED"
        const val STATE_STOPPED = "STOPPED"

        /**
         * Strict parse: a wrong schema or a missing field is an error rather
         * than a zero, so a contract drift shows up instead of reading as
         * "nobody is connected". Error messages never echo the input.
         */
        fun parse(json: String): ExitNodeStats {
            val o = try {
                JSONObject(json)
            } catch (_: Exception) {
                throw IllegalArgumentException("exit-node stats are not valid JSON")
            }
            require(o.optInt("schema", -1) == SCHEMA) { "unsupported exit-node stats schema" }
            try {
                return ExitNodeStats(
                    state = o.getString("state"),
                    openAdmission = when (o.getString("admission")) {
                        "open" -> true
                        "approval" -> false
                        else -> throw IllegalArgumentException("unknown admission mode")
                    },
                    startedUnixSec = o.getLong("startedUnixSec"),
                    expiresUnixSec = o.getLong("expiresUnixSec").takeIf { it != 0L },
                    activeTcp = o.getLong("activeTcp"),
                    activeUdp = o.getLong("activeUdp"),
                    totalTcp = o.getLong("totalTcp"),
                    totalUdp = o.getLong("totalUdp"),
                    bytesFromGuests = o.getLong("bytesFromGuests"),
                    bytesToGuests = o.getLong("bytesToGuests"),
                    policyRejections = o.getLong("policyRejections"),
                    capRejections = o.getLong("capRejections"),
                    dialFailures = o.getLong("dialFailures"),
                    pending = o.getJSONArray("pending").mapObjects {
                        PendingDevice(
                            key = it.getString("key"),
                            firstSeenUnixSec = it.getLong("firstSeenUnixSec"),
                            lastSeenUnixSec = it.getLong("lastSeenUnixSec")
                        )
                    },
                    guests = o.getJSONArray("guests").mapObjects {
                        GuestDevice(
                            key = it.getString("key"),
                            approved = it.getBoolean("approved"),
                            firstSeenUnixSec = it.getLong("firstSeenUnixSec"),
                            lastActiveUnixSec = it.getLong("lastActiveUnixSec"),
                            activeTcp = it.getLong("activeTcp"),
                            activeUdp = it.getLong("activeUdp"),
                            flows = it.getLong("flows"),
                            bytesFromGuest = it.getLong("bytesFrom"),
                            bytesToGuest = it.getLong("bytesTo")
                        )
                    }
                )
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (_: Exception) {
                throw IllegalArgumentException("exit-node stats are missing a field")
            }
        }

        private inline fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> =
            List(length()) { transform(getJSONObject(it)) }
    }
}

/**
 * Short, comparable form of a device key ("nodekey:ab12…" -> "ab12-ef90"):
 * enough for the host and guest to read to each other, not a secret.
 */
fun deviceShortId(key: String): String {
    val hex = key.substringAfter(':')
    if (hex.length < 8) return hex
    return hex.take(4) + "-" + hex.takeLast(4)
}
