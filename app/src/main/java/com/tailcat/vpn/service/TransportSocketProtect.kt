package com.tailcat.vpn.service

/**
 * Retroactively [android.net.VpnService.protect]s already-open process sockets.
 *
 * Tailcat [createEngine] calls netns.SetEnabled(false), so Magicsock/DERP sockets
 * opened during prepare lack the netns Control hook. Re-enabling netns only
 * protects *new* sockets. Under Always-on + Block, unprotected transport sockets
 * loop into the TUN after default routes install and can kill the process.
 *
 * Call after [ensureTransportProtect] and before/after installing default routes.
 * TUN/interface FDs should be excluded; protect() on non-sockets is ignored.
 */
object TransportSocketProtect {

    fun listCandidateFds(
        procSelfFdNames: Array<String>?,
        exclude: Set<Int> = emptySet()
    ): List<Int> {
        if (procSelfFdNames == null) return emptyList()
        return procSelfFdNames
            .mapNotNull { it.toIntOrNull() }
            .filter { it >= 0 && it !in exclude }
            .distinct()
            .sorted()
    }

    /**
     * @return number of FDs for which [protect] returned true
     */
    fun protectAll(fds: List<Int>, protect: (Int) -> Boolean): Int {
        var protected = 0
        for (fd in fds) {
            val ok = runCatching { protect(fd) }.getOrDefault(false)
            if (ok) protected++
        }
        return protected
    }
}
