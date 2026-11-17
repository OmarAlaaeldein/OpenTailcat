package com.tailcat.vpn.service

import com.tailcat.vpn.core.exitnode.ExitNodeStats
import com.tailcat.vpn.core.model.TunnelState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ExitNodeEnd { STOPPED, EXPIRED, FAILED }

sealed interface ExitNodeUiState {
    /** Nothing shared. */
    data object Idle : ExitNodeUiState

    data object Starting : ExitNodeUiState

    /** [token] is a credential held in memory only; it is never persisted or logged. */
    data class Sharing(val token: String, val stats: ExitNodeStats) : ExitNodeUiState

    data class Ended(val reason: ExitNodeEnd, val message: String) : ExitNodeUiState
}

/**
 * Lifecycle and policy for sharing this phone as an exit gateway.
 *
 * It owns nothing of the client tunnel. The only coupling is the interlock:
 * the two roles never run together, because with the client tunnel up the
 * node's own outbound dials would be captured by the VPN and loop back into
 * the phone. The interlock is a refusal on both sides ([start] here,
 * [startBlockReason] consulted by TunnelController), and [refresh] ends a
 * session if a race ever lets both run.
 *
 * Methods block on the native engine and must run off the main thread, except
 * [state] reads. There is no coroutine in here on purpose: callers drive
 * [refresh] on their own schedule, which also makes this testable directly.
 */
class ExitNodeController(
    private val engine: NativeExitNode,
    private val tunnelState: () -> TunnelState,
    private val nowSec: () -> Long = { System.currentTimeMillis() / 1000 }
) {
    private val lock = Any()
    private var generation = 0L
    private var statsFailures = 0

    private val _state = MutableStateFlow<ExitNodeUiState>(ExitNodeUiState.Idle)
    val state: StateFlow<ExitNodeUiState> = _state.asStateFlow()

    val availability: EngineAvailability get() = engine.availability

    /** True from the moment a start is accepted until the session ends. */
    val isActive: Boolean
        get() = _state.value.let { it is ExitNodeUiState.Starting || it is ExitNodeUiState.Sharing }

    /** For the client side of the interlock: why the VPN must not start now, or null. */
    fun startBlockReason(): String? =
        if (isActive) "Stop sharing before connecting. Sharing and the VPN cannot run together." else null

    /**
     * Starts a session. Returns a user-facing refusal, or null once sharing is
     * live. The token is then in [state].
     */
    fun start(config: ExitNodeConfig): String? {
        val gen: Long
        synchronized(lock) {
            if (!engine.availability.isAvailable) return engine.availability.message
            if (isActive) return "Already sharing."
            // Order matters: claiming the slot first means a VPN start that
            // checks startBlockReason() after this point is refused.
            if (tunnelState() != TunnelState.DISCONNECTED) {
                return "Disconnect the VPN first. Sharing and the VPN cannot run together."
            }
            gen = ++generation
            statsFailures = 0
            _state.value = ExitNodeUiState.Starting
        }

        val token: String
        val stats: ExitNodeStats
        try {
            token = engine.start(config)
            stats = ExitNodeStats.parse(engine.statsJson())
        } catch (e: Exception) {
            runCatching { engine.stop() }
            val message = e.message?.takeIf { it.isNotBlank() } ?: "Could not start sharing"
            synchronized(lock) {
                if (generation == gen) {
                    _state.value = ExitNodeUiState.Ended(ExitNodeEnd.FAILED, message)
                }
            }
            return message
        }

        synchronized(lock) {
            if (generation != gen) {
                // Stopped while starting: discard the session we just built.
                runCatching { engine.stop() }
                return "Sharing was cancelled."
            }
            _state.value = ExitNodeUiState.Sharing(token, stats)
        }
        return null
    }

    /** Polls the engine once. Called about once a second while sharing. */
    fun refresh() {
        val current = _state.value as? ExitNodeUiState.Sharing ?: return
        val stats = try {
            ExitNodeStats.parse(engine.statsJson())
        } catch (_: Exception) {
            // Never show stale numbers as live: after a few misses, say so.
            val failures = synchronized(lock) { ++statsFailures }
            if (failures >= MAX_STATS_FAILURES) {
                end(current, ExitNodeEnd.FAILED, "Lost contact with the sharing engine. Sharing stopped.")
            }
            return
        }
        synchronized(lock) { statsFailures = 0 }

        when {
            tunnelState() != TunnelState.DISCONNECTED ->
                end(current, ExitNodeEnd.STOPPED, "The VPN connected, so sharing was stopped.")
            stats.isExpired || nowSec() >= stats.expiresUnixSec ->
                end(current, ExitNodeEnd.EXPIRED, "Sharing time is up. The code no longer works.")
            !stats.isRunning ->
                end(current, ExitNodeEnd.STOPPED, "Sharing stopped.")
            else -> synchronized(lock) {
                // Only publish for the session we polled.
                if (_state.value === current) _state.value = current.copy(stats = stats)
            }
        }
    }

    /** Ends the session and discards its keys. Safe to call in any state. */
    fun stop() {
        val wasActive: Boolean
        synchronized(lock) {
            wasActive = isActive
            generation++
            if (wasActive) _state.value = ExitNodeUiState.Ended(ExitNodeEnd.STOPPED, "Sharing stopped.")
        }
        // Outside the lock: native Close is bounded but not instant, and a
        // concurrent start() must be able to observe the new generation.
        runCatching { engine.stop() }
    }

    /** Leaves the Ended screen. */
    fun acknowledgeEnded() {
        synchronized(lock) {
            if (_state.value is ExitNodeUiState.Ended) _state.value = ExitNodeUiState.Idle
        }
    }

    fun approve(deviceKey: String): String? = deviceAction { engine.approve(deviceKey) }
    fun deny(deviceKey: String): String? = deviceAction { engine.deny(deviceKey) }
    fun revoke(deviceKey: String): String? = deviceAction { engine.revoke(deviceKey) }

    private inline fun deviceAction(block: () -> Unit): String? {
        if (_state.value !is ExitNodeUiState.Sharing) return "Not sharing."
        return try {
            block()
            refresh() // show the change now rather than on the next tick
            null
        } catch (e: Exception) {
            e.message?.takeIf { it.isNotBlank() } ?: "That did not work."
        }
    }

    private fun end(from: ExitNodeUiState.Sharing, reason: ExitNodeEnd, message: String) {
        val ended = synchronized(lock) {
            if (_state.value !== from) {
                false
            } else {
                generation++
                _state.value = ExitNodeUiState.Ended(reason, message)
                true
            }
        }
        if (ended) runCatching { engine.stop() }
    }

    private companion object {
        const val MAX_STATS_FAILURES = 3
    }
}
