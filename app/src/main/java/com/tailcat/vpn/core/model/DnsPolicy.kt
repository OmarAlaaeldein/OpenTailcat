package com.tailcat.vpn.core.model

/**
 * Explicit DNS resolution policy for OpenTailcat VPN tunnels.
 */
enum class DnsPolicy {
    PROFILE_RESOLVER,
    FORCED_RESOLVER,
    GATEWAY_RESOLVER;

    companion object {
        fun fromString(value: String?): DnsPolicy {
            if (value == null) return PROFILE_RESOLVER
            return entries.find { it.name.equals(value, ignoreCase = true) } ?: PROFILE_RESOLVER
        }
    }
}
