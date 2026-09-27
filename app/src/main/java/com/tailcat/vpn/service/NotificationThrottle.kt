package com.tailcat.vpn.service

import com.tailcat.vpn.core.model.TunnelState

/**
 * Telemetry arrives every second; re-posting the ongoing notification that
 * often wastes battery. Post at once when the tunnel state changes, otherwise
 * at most every [intervalMs].
 */
object NotificationThrottle {
    const val DEFAULT_INTERVAL_MS = 5_000L

    fun shouldPost(
        state: TunnelState,
        lastPostedState: TunnelState?,
        nowMs: Long,
        lastPostMs: Long,
        intervalMs: Long = DEFAULT_INTERVAL_MS
    ): Boolean = state != lastPostedState || nowMs - lastPostMs >= intervalMs
}
