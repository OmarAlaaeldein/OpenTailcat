package com.tailcat.vpn

import com.tailcat.vpn.service.VpnInterfaceSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class VpnInterfaceSpecTest {

    @Test
    fun warmInterfaceHasOnlyHostRoutes() {
        val routes = VpnInterfaceSpec.defaultRoutes(false)
        assertEquals(2, routes.size)
        // Literal expectations: using the production constants here would let a
        // changed constant keep this test green while installing a default route.
        assertEquals(VpnInterfaceSpec.InetRoute("100.64.0.2", 32), routes[0])
        assertEquals(VpnInterfaceSpec.InetRoute("fd7a:115c:a1e0::2", 128), routes[1])
        assertFalse(
            "the warm interface must never route 0.0.0.0/0 or ::/0",
            routes.any { it.prefixLength == 0 }
        )
    }

    @Test
    fun routedInterfaceInstallsIpv4AndIpv6DefaultRoutes() {
        val routes = VpnInterfaceSpec.defaultRoutes(true)
        assertEquals(2, routes.size)
        assertEquals(VpnInterfaceSpec.InetRoute("0.0.0.0", 0), routes[0])
        assertEquals(VpnInterfaceSpec.InetRoute("::", 0), routes[1])
    }
}
