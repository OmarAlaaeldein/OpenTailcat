package com.tailcat.vpn.service

import com.tailcat.vpn.core.model.NetworkMetrics
import com.tailcat.vpn.core.model.TransportType

object EngineHealth {
    /** Machine-readable reason a live tunnel must be torn down. */
    sealed interface TeardownReason {
        /** No teardown required. */
        data object Healthy : TeardownReason

        /** Native engine entered FAILED (a required packet pump exited). */
        data class PumpFailed(val detail: String?) : TeardownReason

        /** Engine runs but Magicsock reports neither a direct endpoint nor DERP. */
        data object TransportLost : TeardownReason

        /** No fresh native health heartbeat within the live window. */
        data class HealthStale(val ageSec: Long, val state: String) : TeardownReason
    }

    fun shouldConnect(metrics: NetworkMetrics, nowUnixSec: Long): Boolean {
        if (metrics.transportType == TransportType.UNKNOWN) return false
        return metrics.isLiveRunning(nowUnixSec)
    }

    fun shouldTearDown(metrics: NetworkMetrics, nowUnixSec: Long): Boolean =
        teardownReason(metrics, nowUnixSec) != TeardownReason.Healthy

    fun teardownReason(metrics: NetworkMetrics, nowUnixSec: Long): TeardownReason {
        if (metrics.state == "FAILED") {
            return TeardownReason.PumpFailed(metrics.egressAuditError)
        }
        if (metrics.state == "RUNNING" && metrics.transportType == TransportType.UNKNOWN) {
            return TeardownReason.TransportLost
        }
        if (!metrics.isLiveRunning(nowUnixSec)) {
            val age = (nowUnixSec - metrics.healthUnixSec).coerceAtLeast(0L)
            return TeardownReason.HealthStale(ageSec = age, state = metrics.state.ifBlank { "?" })
        }
        return TeardownReason.Healthy
    }

    /** Short user-facing cause for a teardown reason (no raw telemetry). */
    fun shortCause(reason: TeardownReason): String = when (reason) {
        TeardownReason.Healthy -> "tunnel healthy"
        is TeardownReason.PumpFailed ->
            if (reason.detail.isNullOrBlank()) "engine packet pump failed"
            else "engine packet pump failed: ${reason.detail}"
        TeardownReason.TransportLost -> "transport lost (no direct or DERP path)"
        is TeardownReason.HealthStale -> "no fresh engine health for ${reason.ageSec}s (state ${reason.state})"
    }
}
