package com.tailcat.vpn

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkMonitorTest {

    @Test
    fun testNetworkStateJsonStructure() {
        val jsonStr = """
        {
            "isOnline": true,
            "networkType": "WIFI",
            "interfaces": [
                {
                    "name": "wlan0",
                    "addresses": ["192.168.1.50/24"],
                    "mtu": 1500
                }
            ],
            "gateways": ["192.168.1.1"],
            "dnsServers": ["1.1.1.1", "8.8.8.8"]
        }
        """.trimIndent()

        val root = JSONObject(jsonStr)
        assertTrue(root.getBoolean("isOnline"))
        assertEquals("WIFI", root.getString("networkType"))

        val ifArray = root.getJSONArray("interfaces")
        assertEquals(1, ifArray.length())
        val ifObj = ifArray.getJSONObject(0)
        assertEquals("wlan0", ifObj.getString("name"))
        assertEquals(1500, ifObj.getInt("mtu"))
        assertEquals("192.168.1.50/24", ifObj.getJSONArray("addresses").getString(0))

        val gwArray = root.getJSONArray("gateways")
        assertEquals(1, gwArray.length())
        assertEquals("192.168.1.1", gwArray.getString(0))

        val dnsArray = root.getJSONArray("dnsServers")
        assertEquals(2, dnsArray.length())
        assertEquals("1.1.1.1", dnsArray.getString(0))
        assertEquals("8.8.8.8", dnsArray.getString(1))
    }

    @Test
    fun testNetworkStateJsonOffline() {
        val jsonStr = """
        {
            "isOnline": false,
            "networkType": "NONE",
            "interfaces": [],
            "gateways": [],
            "dnsServers": []
        }
        """.trimIndent()

        val root = JSONObject(jsonStr)
        assertFalse(root.getBoolean("isOnline"))
        assertEquals("NONE", root.getString("networkType"))
        assertEquals(0, root.getJSONArray("interfaces").length())
        assertEquals(0, root.getJSONArray("gateways").length())
        assertEquals(0, root.getJSONArray("dnsServers").length())
    }
}
