package com.tailcat.vpn

import com.tailcat.vpn.service.VpnInterfaceSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnInterfaceSpecTest {

    @Test
    fun warmInterfaceHasNoDefaultRoutes() {
        assertTrue(VpnInterfaceSpec.defaultRoutes(false).isEmpty())
    }

    @Test
    fun routedInterfaceInstallsIpv4AndIpv6DefaultRoutes() {
        val routes = VpnInterfaceSpec.defaultRoutes(true)
        assertEquals(2, routes.size)
        assertEquals(VpnInterfaceSpec.InetRoute("0.0.0.0", 0), routes[0])
        assertEquals(VpnInterfaceSpec.InetRoute("::", 0), routes[1])
    }
}
