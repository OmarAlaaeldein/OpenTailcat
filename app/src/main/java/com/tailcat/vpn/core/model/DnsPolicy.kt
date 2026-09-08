package com.tailcat.vpn.core.model

/**
 * Explicit DNS resolution policy for OpenTailcat VPN tunnels.
 *
 * Only PROFILE_RESOLVER and FORCED_RESOLVER are used. Previously persisted
 * "GATEWAY_RESOLVER" strings migrate via [fromString] to PROFILE_RESOLVER.
 */
enum class DnsPolicy {
    PROFILE_RESOLVER,
    FORCED_RESOLVER;

    companion object {
        fun fromString(value: String?): DnsPolicy {
            if (value == null) return PROFILE_RESOLVER
            return entries.find { it.name.equals(value, ignoreCase = true) } ?: PROFILE_RESOLVER
        }
    }
}
