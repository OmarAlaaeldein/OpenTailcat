package com.tailcat.vpn.service

/**
 * Retry policy for VPN starts that were not requested from the UI
 * (Always-on, sticky restarts, and process-restart restores). Those starts
 * often run before the network is usable, so a failed handshake is retried
 * with backoff while the user still wants the VPN.
 */
object StartRetry {

    private const val BASE_DELAY_MS = 2_000L
    const val MAX_DELAY_MS = 30_000L

    /** 2 s, 4 s, 8 s, 16 s, then 30 s for every later attempt. */
    fun delayMs(attempt: Int): Long {
        var delay = BASE_DELAY_MS
        repeat((attempt - 1).coerceAtLeast(0)) {
            if (delay >= MAX_DELAY_MS) return MAX_DELAY_MS
            delay *= 2
        }
        return delay.coerceAtMost(MAX_DELAY_MS)
    }

    /**
     * Engine errors that no retry can fix. Prefixes match the native
     * `Prepare` errors in core-engine/lifecycle.go and the Kotlin engine
     * loader in [TunnelEngine].
     */
    fun isPermanentEngineError(message: String?): Boolean {
        if (message == null) return false
        return PERMANENT_PREFIXES.any { message.startsWith(it) }
    }

    private val PERMANENT_PREFIXES = listOf(
        "token rejected",
        "token classification",
        "VPN engine is missing"
    )
}

/** A start failure caused by configuration or consent, not the network. */
class PermanentStartFailure(message: String) : IllegalStateException(message)
