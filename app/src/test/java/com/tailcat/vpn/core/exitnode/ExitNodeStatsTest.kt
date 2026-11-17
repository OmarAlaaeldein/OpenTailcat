package com.tailcat.vpn.core.exitnode

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kotlin half of the stats contract; Go writes core-engine/testdata/exitnode_stats.json. */
class ExitNodeStatsTest {

    private fun fixture(): String {
        val stream = javaClass.classLoader?.getResourceAsStream("exitnode_stats.json")
        assertNotNull("exitnode_stats.json is missing from test resources", stream)
        return stream!!.bufferedReader().use { it.readText() }
    }

    @Test
    fun zeroExpiryMeansNoTimeLimit() {
        val noLimit = JSONObject(fixture()).put("expiresUnixSec", 0).toString()
        assertEquals(null, ExitNodeStats.parse(noLimit).expiresUnixSec)
    }

    @Test
    fun parsesTheNativeFixture() {
        val s = ExitNodeStats.parse(fixture())
        assertTrue(s.isRunning)
        assertFalse(s.openAdmission)
        assertEquals(1800000000L, s.startedUnixSec)
        assertEquals(1800003600L, s.expiresUnixSec)
        assertEquals(3L, s.activeFlows)
        assertEquals(120345L + 9876543L, s.totalBytes)
        assertEquals(3L, s.policyRejections)
        assertEquals(5L, s.dialFailures)

        assertEquals(1, s.pending.size)
        assertEquals(1800000160L, s.pending.single().lastSeenUnixSec)

        val guest = s.guests.single()
        assertTrue(guest.approved)
        assertEquals(3L, guest.activeFlows)
        assertEquals(50L, guest.flows)
        assertEquals(120345L, guest.bytesFromGuest)
        assertEquals(9876543L, guest.bytesToGuest)
    }

    @Test
    fun oneRequestIsNotSuspiciousButTwoAre() {
        val one = ExitNodeStats.parse(fixture())
        assertFalse(one.suspiciousRequests)

        val json = JSONObject(fixture())
        val pending = json.getJSONArray("pending")
        pending.put(JSONObject(pending.getJSONObject(0).toString()).put("key", "nodekey:" + "ab".repeat(32)))
        assertTrue(ExitNodeStats.parse(json.toString()).suspiciousRequests)
    }

    @Test
    fun anUnknownSchemaIsRejectedRatherThanReadAsZeros() {
        val json = JSONObject(fixture()).put("schema", 2).toString()
        assertThrows(IllegalArgumentException::class.java) { ExitNodeStats.parse(json) }
    }

    @Test
    fun aMissingFieldIsRejectedRatherThanReadAsZero() {
        val json = JSONObject(fixture())
        json.remove("bytesToGuests")
        assertThrows(IllegalArgumentException::class.java) { ExitNodeStats.parse(json.toString()) }
    }

    @Test
    fun anUnknownAdmissionModeIsRejected() {
        val json = JSONObject(fixture()).put("admission", "everyone").toString()
        assertThrows(IllegalArgumentException::class.java) { ExitNodeStats.parse(json) }
    }

    @Test
    fun garbageIsRejectedWithoutEchoingIt() {
        val e = assertThrows(IllegalArgumentException::class.java) { ExitNodeStats.parse("tc-not-json-secret") }
        assertFalse(e.message.orEmpty().contains("secret"))
    }

    @Test
    fun shortIdIsReadableAndStable() {
        assertEquals("0011-eeff", deviceShortId("nodekey:" + "00112233445566778899aabbccddeeff".repeat(2)))
        assertEquals("abc", deviceShortId("nodekey:abc"))
    }
}
