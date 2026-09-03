package com.tailcat.vpn.data

import com.tailcat.vpn.core.model.DnsPolicy
import com.tailcat.vpn.core.token.TokenParser
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FakePreferencesStorage : PreferencesStorage {
    override var activeProfileId: String? = null
    override var defaultMtu: Int = 1280
    override var defaultDns: String = "1.1.1.1"
    override var splitTunnelExcludedApps: Set<String> = emptySet()
    override var savedProfilesJson: String? = null
}

class ProfileRepositoryTest {

    private lateinit var fakeStorage: FakePreferencesStorage
    private lateinit var repository: ProfileRepository

    // Pinned official token vector from test corpus (official_valid_short)
    private val validOfficialToken = "tco2FwWCAHo3y8FCCTyLdV3BsQ6Gy0JjdK0WqoU-0L38CyuG0cfGFrWCBYaa_0UFSXMsuq7V5d-bMKbaMcsOV0K61a1KGnaPGme2FpGQEu"

    @Before
    fun setUp() {
        fakeStorage = FakePreferencesStorage()
        repository = ProfileRepository(fakeStorage)
    }

    @Test
    fun testAddProfileWithValidDnsAndPolicy() {
        val result = repository.addOrUpdateFromToken(
            name = "Test Gateway",
            rawToken = validOfficialToken,
            customDns = "9.9.9.9",
            dnsPolicy = DnsPolicy.PROFILE_RESOLVER
        )

        assertTrue("Expected profile addition to succeed", result.isSuccess)
        val profile = result.getOrNull()
        assertNotNull(profile)
        assertEquals("9.9.9.9", profile!!.customDns)
        assertEquals(DnsPolicy.PROFILE_RESOLVER, profile.dnsPolicy)
        assertEquals("Test Gateway", profile.name)

        // Verify active profile
        assertEquals(profile.id, repository.activeProfile.value?.id)
        assertEquals(profile.id, fakeStorage.activeProfileId)

        // Verify JSON persistence
        assertNotNull(fakeStorage.savedProfilesJson)
        val array = JSONArray(fakeStorage.savedProfilesJson)
        assertEquals(1, array.length())
        val savedObj = array.getJSONObject(0)
        assertEquals("9.9.9.9", savedObj.getString("customDns"))
        assertEquals("PROFILE_RESOLVER", savedObj.getString("dnsPolicy"))
    }

    @Test
    fun testAddProfileWithForcedResolverPolicy() {
        val result = repository.addOrUpdateFromToken(
            name = "Forced Gateway",
            rawToken = validOfficialToken,
            customDns = "8.8.8.8",
            dnsPolicy = DnsPolicy.FORCED_RESOLVER
        )

        assertTrue(result.isSuccess)
        val profile = result.getOrNull()!!
        assertEquals("8.8.8.8", profile.customDns)
        assertEquals(DnsPolicy.FORCED_RESOLVER, profile.dnsPolicy)
    }

    @Test
    fun testAddProfileWithInvalidDnsFails() {
        val invalidIps = listOf(
            "not-an-ip",
            "999.999.999.999",
            "127.0.0.1", // loopback rejected
            "255.255.255.255", // broadcast rejected
            "",
            "dns.google.com"
        )

        for (invalid in invalidIps) {
            val result = repository.addOrUpdateFromToken(
                name = "Bad DNS Gateway",
                rawToken = validOfficialToken,
                customDns = invalid
            )
            assertFalse("Expected DNS '$invalid' to be rejected", result.isSuccess)
            val error = result.exceptionOrNull()
            assertNotNull(error)
            assertTrue(
                "Error message should mention invalid DNS: ${error?.message}",
                error?.message?.contains("DNS") == true
            )
        }
    }

    @Test
    fun testUpdateProfileDns() {
        val addResult = repository.addOrUpdateFromToken(
            name = "Mutable DNS Gateway",
            rawToken = validOfficialToken,
            customDns = "1.1.1.1"
        )
        assertTrue(addResult.isSuccess)
        val profile = addResult.getOrNull()!!

        // Update with valid Quad9 IPv6
        val updateResult = repository.updateProfileDns(
            profileId = profile.id,
            customDns = "2620:fe::fe",
            dnsPolicy = DnsPolicy.FORCED_RESOLVER
        )
        assertTrue(updateResult.isSuccess)
        val updated = updateResult.getOrNull()!!
        assertEquals("2620:fe::fe", updated.customDns)
        assertEquals(DnsPolicy.FORCED_RESOLVER, updated.dnsPolicy)

        // Attempt update with invalid IP fails
        val badUpdate = repository.updateProfileDns(
            profileId = profile.id,
            customDns = "invalid-dns",
            dnsPolicy = DnsPolicy.PROFILE_RESOLVER
        )
        assertFalse(badUpdate.isSuccess)
    }

    @Test
    fun testLoadProfilesWithFallbackForCorruptDns() {
        val savedArray = JSONArray().apply {
            put(JSONObject().apply {
                put("id", "profile-1")
                put("name", "Corrupt DNS Gateway")
                put("token", validOfficialToken)
                put("serverPublicKey", "aabbcc")
                put("customDns", "malformed-ip-address")
                put("dnsPolicy", "UNKNOWN_POLICY")
                put("mtu", 1280)
                put("isDefault", true)
                put("createdAt", 1000L)
            })
        }
        fakeStorage.savedProfilesJson = savedArray.toString()

        val reloadedRepo = ProfileRepository(fakeStorage)
        val profiles = reloadedRepo.profiles.value
        assertEquals(1, profiles.size)
        val p = profiles[0]
        // Malformed IP in storage falls back to safe 1.1.1.1
        assertEquals("1.1.1.1", p.customDns)
        // Unknown policy falls back to PROFILE_RESOLVER
        assertEquals(DnsPolicy.PROFILE_RESOLVER, p.dnsPolicy)
    }
}
