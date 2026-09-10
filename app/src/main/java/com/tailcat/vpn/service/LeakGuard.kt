package com.tailcat.vpn.service

object LeakGuard {
    /** API level where Always-on + block-without-VPN settings exist and are recommended. */
    const val LOCKDOWN_REQUIRED_API = 29

    /**
     * Soft tip for UI copy. Never returned from [refusalReason] /
     * [refusalReasonForStartup] — Connect and default routes work without lockdown.
     */
    const val LOCKDOWN_RECOMMENDED =
        "For stronger leak protection, enable Always-on VPN and ‘Block connections without VPN’ in Android VPN settings"

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
        // lockdownEnabled is retained for call-site compatibility; it never blocks.
        if (!splitTunnelEmpty) return SPLIT_TUNNEL_BLOCKED
        return null
    }

    /**
     * Split-tunnel exclusions still refuse Connect. Always-on / lockdown is
     * optional — [LockdownProbe] remains available for status display only.
     */
    fun refusalReasonForStartup(
        sdkInt: Int,
        settingsLockdown: Boolean?,
        frameworkLockdownEnabled: Boolean,
        splitTunnelEmpty: Boolean
    ): String? {
        if (!splitTunnelEmpty) return SPLIT_TUNNEL_BLOCKED
        return null
    }
}
