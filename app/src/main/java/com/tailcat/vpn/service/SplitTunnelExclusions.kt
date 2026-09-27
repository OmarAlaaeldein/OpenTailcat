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

    /** First UID of ordinary apps ([android.os.Process.FIRST_APPLICATION_UID]). */
    const val FIRST_APPLICATION_UID = 10_000

    /**
     * Android applies exclusions per UID: excluding one package also excludes
     * every package sharing its UID. Returns, for each package, the names of
     * the other packages that share its UID (empty when it has its own).
     */
    fun sharedUidPeers(apps: List<Triple<String, String, Int>>): Map<String, List<String>> {
        val byUid = apps.groupBy { it.third }
        return apps.associate { (pkg, _, uid) ->
            pkg to byUid.getValue(uid).filter { it.first != pkg }.map { it.second }.sorted()
        }
    }

    fun isSystemUid(uid: Int): Boolean = uid < FIRST_APPLICATION_UID
}
