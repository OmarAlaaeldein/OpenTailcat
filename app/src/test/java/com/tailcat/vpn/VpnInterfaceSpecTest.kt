package com.tailcat.vpn

import com.tailcat.vpn.service.VpnInterfaceSpec
import org.junit.Assert.assertEquals
import org.junit.Test

class VpnInterfaceSpecTest {

    @Test
    fun warmInterfaceHasOnlyHostRoutes() {
        val routes = VpnInterfaceSpec.defaultRoutes(false)
        assertEquals(2, routes.size)
        assertEquals(VpnInterfaceSpec.InetRoute(VpnInterfaceSpec.IPV4_ADDRESS, VpnInterfaceSpec.IPV4_PREFIX), routes[0])
        assertEquals(VpnInterfaceSpec.InetRoute(VpnInterfaceSpec.IPV6_ADDRESS, VpnInterfaceSpec.IPV6_PREFIX), routes[1])
    }

    @Test
    fun routedInterfaceInstallsIpv4AndIpv6DefaultRoutes() {
        val routes = VpnInterfaceSpec.defaultRoutes(true)
        assertEquals(2, routes.size)
        assertEquals(VpnInterfaceSpec.InetRoute("0.0.0.0", 0), routes[0])
        assertEquals(VpnInterfaceSpec.InetRoute("::", 0), routes[1])
    }
}
