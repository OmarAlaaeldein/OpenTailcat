package com.tailcat.vpn.service

enum class ExitNodeAdmission {
    /** Unknown devices wait for the host's approval. The safe default. */
    APPROVAL,

    /** Any holder of the code connects until it expires. Always time-limited. */
    OPEN
}

/**
 * A relay the host runs, instead of the nearest public one. The region is
 * embedded in the code this phone mints, so a guest needs no configuration of
 * its own: pairing is enough.
 *
 * A relay only ever sees WireGuard ciphertext, but a public relay still sees
 * the metadata (which keys talk, when, and how much), and "nearest" is picked
 * from the host's latency alone. Naming a private relay keeps that first-party
 * and lets the operator place it for the pair.
 *
 * [hostname] must be the DNS name the relay's TLS certificate covers. Ports
 * default to the standard DERP and STUN ones.
 */
data class ExitNodeRelay(
    val hostname: String,
    val derpPort: Int = DEFAULT_DERP_PORT,
    val stunPort: Int = DEFAULT_STUN_PORT
) {
    init {
        val problem = validateHostname(hostname)
        require(problem == null) { problem ?: "" }
        require(derpPort in 1..65535) { "Relay DERP port must be between 1 and 65535" }
        require(stunPort in 1..65535) { "Relay STUN port must be between 1 and 65535" }
    }

    /** The host name without incidental whitespace from a text field. */
    val host: String get() = hostname.trim()

    companion object {
        const val DEFAULT_DERP_PORT = 443
        const val DEFAULT_STUN_PORT = 3478

        /**
         * A user-facing problem with [hostname], or null when it looks usable.
         * The native engine is still the authority; this only fails fast in the
         * UI. Kept in step with `exitNodeRelayJSON.region` in the Go bridge.
         */
        fun validateHostname(hostname: String): String? {
            val host = hostname.trim()
            if (host.isEmpty()) return "Enter your relay's host name"
            if (host.any { it in "/?#@: \t\\" }) {
                return "Use a host name only, not a URL or host:port"
            }
            val lower = host.lowercase()
            if (lower == "localhost" || lower.endsWith(".localhost") || lower == "ip6-localhost") {
                return "localhost cannot be used as a relay"
            }
            if (lower == "metadata.google.internal" || lower == "metadata.internal") {
                return "That host cannot be used as a relay"
            }
            val labels = host.split('.')
            if (labels.size == 4 && labels.all(::isIpv4Label)) {
                return "Use a DNS name, not an IP address"
            }
            return null
        }

        private fun isIpv4Label(part: String): Boolean {
            if (part.isEmpty() || part.length > 3 || !part.all(Char::isDigit)) return false
            // Leading zeroes are not dotted-decimal, matching the token parser.
            if (part.length > 1 && part.startsWith("0")) return false
            return (part.toIntOrNull() ?: return false) in 0..255
        }

        /** Validates the three text fields the share screen edits. */
        fun problem(hostname: String, derpPort: String, stunPort: String): String? {
            validateHostname(hostname)?.let { return it }
            if (derpPort.toIntOrNull() !in 1..65535) return "DERP port must be between 1 and 65535"
            if (stunPort.toIntOrNull() !in 1..65535) return "STUN port must be between 1 and 65535"
            return null
        }
    }
}

/**
 * One sharing session. [ttlSeconds] null means no time limit: the session
 * lasts until the host stops it (or Android kills the app). That is allowed
 * only with [ExitNodeAdmission.APPROVAL], here and natively, so a leaked code
 * never grants standing access on its own.
 *
 * [relay] null uses the nearest public relay.
 */
data class ExitNodeConfig(
    val ttlSeconds: Long?,
    val admission: ExitNodeAdmission = ExitNodeAdmission.APPROVAL,
    val relay: ExitNodeRelay? = null
) {
    init {
        if (ttlSeconds == null) {
            require(admission == ExitNodeAdmission.APPROVAL) {
                "Sharing without a time limit needs approval for each device"
            }
        } else {
            require(ttlSeconds in 1..MAX_TTL_SECONDS) { "Sharing time must be between 1 second and 24 hours" }
        }
    }

    val hasTimeLimit: Boolean get() = ttlSeconds != null

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
