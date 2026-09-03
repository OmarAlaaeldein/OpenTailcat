package com.tailcat.vpn.service

object VpnInterfaceSpec {
    const val IPV4_ADDRESS = "100.64.0.2"
    const val IPV4_PREFIX = 32
    const val IPV6_ADDRESS = "fd7a:115c:a1e0::2"
    const val IPV6_PREFIX = 128

    data class InetRoute(val address: String, val prefixLength: Int)

    fun defaultRoutes(enabled: Boolean): List<InetRoute> {
        if (!enabled) return emptyList()
        return listOf(
            InetRoute("0.0.0.0", 0),
            InetRoute("::", 0)
        )
    }
}
