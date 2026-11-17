package com.tailcat.vpn.service

enum class ExitNodeAdmission {
    /** Unknown devices wait for the host's approval. The safe default. */
    APPROVAL,

    /** Any holder of the code connects until it expires. */
    OPEN
}

data class ExitNodeConfig(
    val ttlSeconds: Long,
    val admission: ExitNodeAdmission = ExitNodeAdmission.APPROVAL
) {
    init {
        require(ttlSeconds in 1..MAX_TTL_SECONDS) { "Sharing time must be between 1 second and 24 hours" }
    }

    companion object {
        const val MAX_TTL_SECONDS = 24 * 60 * 60L
    }
}

/**
 * The host side of a Tailcat session: this phone as the exit gateway. It is
 * separate from [NativeEngine], the client tunnel, and shares none of its
 * state; see AGENTS.md for why the two never run together.
 *
 * Calls block and must not run on the main thread.
 */
interface NativeExitNode {
    val availability: EngineAvailability

    /** Starts a session with fresh keys and returns its `tc...` code. A credential: never log it. */
    fun start(config: ExitNodeConfig): String

    /** JSON in the schema [com.tailcat.vpn.core.exitnode.ExitNodeStats] parses. */
    fun statsJson(): String

    fun approve(deviceKey: String)
    fun deny(deviceKey: String)

    /** Withdraws a device's access and drops its connection. */
    fun revoke(deviceKey: String): Boolean

    /** Idempotent, bounded. Discarding the session's keys revokes every code it issued. */
    fun stop()
}

/**
 * Used by builds whose native engine has no exit-node exports. Reports why, so
 * the UI says so instead of offering a switch that does nothing.
 */
object UnavailableExitNode : NativeExitNode {
    override val availability = EngineAvailability(
        isAvailable = false,
        message = "Sharing is not included in this build's native engine."
    )

    private fun unavailable(): Nothing = throw UnsupportedOperationException(availability.message)

    override fun start(config: ExitNodeConfig): String = unavailable()
    override fun statsJson(): String = unavailable()
    override fun approve(deviceKey: String) = unavailable()
    override fun deny(deviceKey: String) = unavailable()
    override fun revoke(deviceKey: String): Boolean = unavailable()
    override fun stop() = Unit
}
