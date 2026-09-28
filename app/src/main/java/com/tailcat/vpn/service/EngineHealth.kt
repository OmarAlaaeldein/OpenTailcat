package com.tailcat.vpn.service

import com.tailcat.vpn.core.model.NetworkMetrics
import com.tailcat.vpn.core.model.TransportType

object EngineHealth {
    /**
     * Consecutive HealthStale poll observations required before teardown.
     * PumpFailed and GatewayLost still tear down on the first observation.
     * With a 1s metrics poll, three stale polls after the freshness window
     * already expired avoids single-sample flaps without hiding a dead engine.
     */
    const val STALE_TEARDOWN_POLLS: Int = 3

    /**
     * Seconds without any gateway reply (DiscoPing every ~5 s, 2 s timeout)
     * after which a running session counts as lost and is reconnected. A
     * healthy relayed session answers DiscoPing, so this needs about 12
     * consecutive failures.
     */
    const val GATEWAY_LOSS_SEC: Long = 60L

    /** Machine-readable reason a live tunnel must be torn down. */
    sealed interface TeardownReason {
        /** No teardown required. */
        data object Healthy : TeardownReason

        /** Native engine entered FAILED (a required packet pump exited). */
        data class PumpFailed(val detail: String?) : TeardownReason

        /** No fresh native health heartbeat within the live window. */
        data class HealthStale(val ageSec: Long, val state: String) : TeardownReason

        /** Pumps are alive but the gateway has not answered for [ageSec]. */
        data class GatewayLost(val ageSec: Long) : TeardownReason
    }

    fun shouldConnect(metrics: NetworkMetrics, nowUnixSec: Long): Boolean {
        if (metrics.transportType == TransportType.UNKNOWN) return false
        return metrics.isLiveRunning(nowUnixSec)
    }

    /**
     * Whether a HealthStale observation should tear down after
     * [consecutiveStalePolls] inclusive counts (1 = this poll).
     */
    fun stalePollsRequireTeardown(consecutiveStalePolls: Int): Boolean =
        consecutiveStalePolls >= STALE_TEARDOWN_POLLS

    /**
     * Next consecutive HealthStale counter given this poll's [reason].
     * Non-stale reasons reset the counter to 0 (caller still applies
     * immediate teardown for PumpFailed / GatewayLost).
     */
    fun nextStalePollCount(reason: TeardownReason, consecutiveStalePolls: Int): Int =
        if (reason is TeardownReason.HealthStale) consecutiveStalePolls + 1 else 0

    fun teardownReason(metrics: NetworkMetrics, nowUnixSec: Long): TeardownReason {
        if (metrics.state == "FAILED") {
            return TeardownReason.PumpFailed(metrics.egressAuditError)
        }
        if (!metrics.isLiveRunning(nowUnixSec)) {
            val age = (nowUnixSec - metrics.healthUnixSec).coerceAtLeast(0L)
            return TeardownReason.HealthStale(ageSec = age, state = metrics.state.ifBlank { "?" })
        }
        if (metrics.discoStale && metrics.lastDiscoOkUnixSec > 0L) {
            val silentSec = nowUnixSec - metrics.lastDiscoOkUnixSec
            if (silentSec >= GATEWAY_LOSS_SEC) {
                return TeardownReason.GatewayLost(ageSec = silentSec)
            }
        }
        return TeardownReason.Healthy
    }

    /** Short user-facing cause for a teardown reason (no raw telemetry). */
    fun shortCause(reason: TeardownReason): String = when (reason) {
        TeardownReason.Healthy -> "tunnel healthy"
        is TeardownReason.PumpFailed ->
            if (reason.detail.isNullOrBlank()) "engine packet pump failed"
            else "engine packet pump failed: ${reason.detail}"
        is TeardownReason.HealthStale -> "no fresh engine health for ${reason.ageSec}s (state ${reason.state})"
        is TeardownReason.GatewayLost -> "gateway not responding for ${reason.ageSec}s"
    }
}
