package com.tailcat.vpn.service

object LeakGuard {
    const val LOCKDOWN_REQUIRED_API = 29

    const val LOCKDOWN_REQUIRED =
        "Enable Always-on VPN and ‘Block connections without VPN’ in Android VPN settings before connecting"

    const val SPLIT_TUNNEL_BLOCKED =
        "Disable split-tunnel exclusions before connecting; excluded apps bypass the VPN"

    fun mayInstallDefaultRoutes(
        sdkInt: Int,
        lockdownEnabled: Boolean,
        splitTunnelEmpty: Boolean
    ): Boolean = refusalReason(sdkInt, lockdownEnabled, splitTunnelEmpty) == null

    fun refusalReason(
        sdkInt: Int,
        lockdownEnabled: Boolean,
        splitTunnelEmpty: Boolean
    ): String? {
        if (!splitTunnelEmpty) return SPLIT_TUNNEL_BLOCKED
        if (sdkInt >= LOCKDOWN_REQUIRED_API && !lockdownEnabled) return LOCKDOWN_REQUIRED
        return null
    }
}