package com.tailcat.vpn.service

import com.tailcat.vpn.core.exitnode.ExitNodeStats
import com.tailcat.vpn.core.metrics.TrafficFormat

/**
 * The words in the sharing notifications, kept pure so they can be tested.
 * Nothing here may include a code, a device key, or a destination: a
 * notification is visible to anyone who can see the lock screen.
 */
object ExitNodeNotificationText {
    const val TITLE = "Sharing this phone"
    const val STARTING = "Starting…"
    const val PENDING_TITLE = "Device connection request"
    const val PENDING_TEXT = "Open OpenTailcat to review the request."
    const val NO_LIMIT = "no time limit"

    /** Body of the ongoing notification while a session is live. */
    fun sharingText(stats: ExitNodeStats, nowSec: Long): String {
        val expires = stats.expiresUnixSec
        val left = if (expires == null) NO_LIMIT else formatLeft(expires - nowSec) + " left"
        val devices = stats.guests.count { it.activeFlows > 0 }
        val who = when {
            stats.pending.isNotEmpty() -> "${stats.pending.size} waiting for approval"
            devices == 1 -> "1 device"
            devices > 1 -> "$devices devices"
            else -> "no devices"
        }
        return "$who · ${TrafficFormat.formatBytes(stats.totalBytes)} · $left"
    }

    /** Body of the notification left behind when a session ends. */
    fun endedTitle(reason: ExitNodeEnd): String = when (reason) {
        ExitNodeEnd.EXPIRED -> "Sharing finished"
        ExitNodeEnd.STOPPED -> "Sharing stopped"
        ExitNodeEnd.FAILED -> "Sharing could not continue"
    }

    fun formatLeft(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        return when {
            s >= 3600 -> "${s / 3600} h ${(s % 3600) / 60} min"
            s >= 60 -> "${s / 60} min"
            else -> "$s s"
        }
    }
}
