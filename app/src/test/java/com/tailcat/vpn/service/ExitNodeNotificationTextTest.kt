package com.tailcat.vpn.service

import com.tailcat.vpn.core.exitnode.ExitNodeStats
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ExitNodeNotificationTextTest {

    private val now = 1_800_000_000L
    private val secretKey = "nodekey:" + "ab".repeat(32)

    private fun stats(
        pending: Int = 0,
        activeGuests: Int = 0,
        bytes: Long = 0,
        expiresIn: Long = 3600
    ): ExitNodeStats {
        val guests = JSONArray()
        repeat(activeGuests) { i ->
            guests.put(
                JSONObject().put("key", "nodekey:" + "%064x".format(i)).put("approved", true)
                    .put("firstSeenUnixSec", now).put("lastActiveUnixSec", now)
                    .put("activeTcp", 1).put("activeUdp", 0).put("flows", 1)
                    .put("bytesFrom", 0).put("bytesTo", 0)
            )
        }
        val pend = JSONArray()
        repeat(pending) {
            pend.put(JSONObject().put("key", secretKey).put("firstSeenUnixSec", now).put("lastSeenUnixSec", now))
        }
        return ExitNodeStats.parse(
            JSONObject().put("schema", 1).put("state", "RUNNING").put("admission", "approval")
                .put("startedUnixSec", now).put("expiresUnixSec", now + expiresIn)
                .put("activeTcp", activeGuests).put("activeUdp", 0).put("totalTcp", 0).put("totalUdp", 0)
                .put("bytesFromGuests", bytes).put("bytesToGuests", 0)
                .put("policyRejections", 0).put("capRejections", 0).put("dialFailures", 0)
                .put("pending", pend).put("guests", guests).toString()
        )
    }

    @Test
    fun describesAnEmptyShare() {
        assertEquals("no devices yet · 0 B · 1 h 0 min left", ExitNodeNotificationText.sharingText(stats(), now))
    }

    @Test
    fun countsOnlyDevicesWithOpenFlows() {
        assertEquals("1 device · 1.5 kB · 59 min left",
            ExitNodeNotificationText.sharingText(stats(activeGuests = 1, bytes = 1500, expiresIn = 3599), now))
        assertEquals("3 devices · 0 B · 1 h 0 min left",
            ExitNodeNotificationText.sharingText(stats(activeGuests = 3), now))
    }

    @Test
    fun aWaitingRequestTakesPriorityOverTheDeviceCount() {
        val text = ExitNodeNotificationText.sharingText(stats(pending = 2, activeGuests = 1), now)
        assertEquals("2 waiting for approval · 0 B · 1 h 0 min left", text)
    }

    @Test
    fun nothingSecretReachesANotification() {
        val text = ExitNodeNotificationText.sharingText(stats(pending = 1), now)
        assertFalse(text.contains("nodekey"))
        assertFalse(text.contains("ab".repeat(8)))
        assertFalse(ExitNodeNotificationText.PENDING_TEXT.contains("nodekey"))
    }

    @Test
    fun timeLeftNeverGoesNegative() {
        assertEquals("0 s", ExitNodeNotificationText.formatLeft(-30))
        assertEquals("45 s", ExitNodeNotificationText.formatLeft(45))
        assertEquals("2 min", ExitNodeNotificationText.formatLeft(120))
        assertEquals("1 h 1 min", ExitNodeNotificationText.formatLeft(3660))
    }

    @Test
    fun endedTitlesAreDistinct() {
        assertEquals(3, ExitNodeEnd.entries.map(ExitNodeNotificationText::endedTitle).toSet().size)
    }
}
