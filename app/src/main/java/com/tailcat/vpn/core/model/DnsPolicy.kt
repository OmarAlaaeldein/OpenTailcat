package com.tailcat.vpn.core.model

/**
 * Explicit DNS resolution policy for OpenTailcat VPN tunnels.
 */
enum class DnsPolicy(val displayName: String, val description: String) {
    /**
     * Preserves the original DNS destination IP from the TUN datagram and proxies
     * it through the WireGuard tunnel, configuring the profile's selected DNS server
     * in the Android VPN service.
     */
    PROFILE_RESOLVER(
        "Profile Resolver",
        "Queries the profile's configured resolver through the encrypted tunnel with automatic TCP fallback."
    ),

    /**
     * Enforces that all port-53 queries are redirected through the tunnel to a specific
     * forced secure resolver, ignoring destination addresses set by individual apps.
     */
    FORCED_RESOLVER(
        "Forced Resolver",
        "Reroutes all DNS queries through the tunnel to the selected secure resolver."
    ),

    /**
     * Resolves domain names through the gateway's internal resolver when advertised.
     */
    GATEWAY_RESOLVER(
        "Gateway Resolver",
        "Uses the gateway's internal DNS resolver."
    );

    companion object {
        fun fromString(value: String?): DnsPolicy {
            if (value == null) return PROFILE_RESOLVER
            return entries.find { it.name.equals(value, ignoreCase = true) } ?: PROFILE_RESOLVER
        }
    }
}
