package com.tailcat.vpn.service

/**
 * Validates stored split-tunnel exclusions before they are handed to
 * [android.net.VpnService.Builder.addDisallowedApplication].
 *
 * Checked apps intentionally bypass the VPN over their ordinary OS network
 * access. The tunnel is not leak-free while any exclusion is set; that is the
 * documented purpose of the feature and is never presented as leak protection.
 */
object SplitTunnelExclusions {

    /**
     * Filters the stored package set for the current VPN build: blank entries
     * and packages no longer installed are skipped so a stale entry cannot
     * fail [android.net.VpnService.Builder.establish]. The result order is
     * deterministic.
     */
    fun validPackages(
        excluded: Set<String>,
        isInstalled: (String) -> Boolean
    ): List<String> = excluded
        .filter { it.isNotBlank() && isInstalled(it) }
        .sorted()
}
