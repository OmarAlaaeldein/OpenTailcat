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

/**
 * Standard trusted public resolver presets for easy selection.
 */
data class DnsPreset(
    val name: String,
    val ipv4: String,
    val ipv6: String,
    val description: String
) {
    companion object {
        val CLOUDFLARE = DnsPreset(
            name = "Cloudflare",
            ipv4 = "1.1.1.1",
            ipv6 = "2606:4700:4700::1111",
            description = "Fast, privacy-focused public resolver (1.1.1.1)"
        )
        val QUAD9 = DnsPreset(
            name = "Quad9",
            ipv4 = "9.9.9.9",
            ipv6 = "2620:fe::fe",
            description = "Malware-blocking secure resolver (9.9.9.9)"
        )
        val GOOGLE = DnsPreset(
            name = "Google Public DNS",
            ipv4 = "8.8.8.8",
            ipv6 = "2001:4860:4860::8888",
            description = "Reliable global public resolver (8.8.8.8)"
        )

        val ALL_PRESETS = listOf(CLOUDFLARE, QUAD9, GOOGLE)
    }
}
