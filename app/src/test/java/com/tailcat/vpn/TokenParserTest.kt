package com.tailcat.vpn

import com.tailcat.vpn.core.token.TokenClassification
import com.tailcat.vpn.core.token.TokenParser
import com.tailcat.vpn.core.token.TokenValidationState
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenParserTest {

    @Test
    fun testAllSharedCrossLanguageFixtures() {
        // Gradle declares core-engine/testdata as this test source set's resource directory.
        val stream = javaClass.classLoader?.getResourceAsStream("token_fixtures.json")
        assertNotNull("Canonical token fixture corpus was not found", stream)
        val jsonText = stream!!.bufferedReader().use { it.readText() }

        val array = JSONArray(jsonText)
        assertTrue("Fixtures array must not be empty", array.length() > 0)

        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val name = obj.getString("name")
            val token = obj.getString("token")
            val expectedClassification = obj.getString("expectedClassification")
            val expectedErrorCode = obj.optString("expectedErrorCode", "")
            val expectedNodeKeyHex = obj.optString("expectedNodeKeyHex", "")
            val expectedDiscoKeyHex = obj.optString("expectedDiscoKeyHex", "")
            val expectedRegionId = obj.optInt("expectedRegionId", 0)
            val hasEmbeddedRegion = obj.optBoolean("hasEmbeddedRegion", false)

            val parsed = TokenParser.parse(token)

            assertEquals(
                "[$name] Classification mismatch",
                expectedClassification,
                parsed.classification.name
            )

            if (expectedClassification == "VALID_OFFICIAL_SHORT" || expectedClassification == "VALID_OFFICIAL_RESOLVED") {
                assertTrue("[$name] Valid token must be connectable", parsed.isConnectable)
                assertFalse("[$name] Valid token must not be expired", parsed.isExpired)

                if (expectedNodeKeyHex.isNotEmpty()) {
                    assertEquals("[$name] NodeKeyHex mismatch", expectedNodeKeyHex, parsed.serverPublicKeyHex)
                }
                if (expectedDiscoKeyHex.isNotEmpty()) {
                    assertEquals("[$name] DiscoKeyHex mismatch", expectedDiscoKeyHex, parsed.serverDiscoKeyHex)
                }
                if (parsed.serverDiscoKeyHex != null) {
                    assertFalse(
                        "[$name] NodeKey and DiscoKey must be separate (p != k)",
                        parsed.serverPublicKeyHex.equals(parsed.serverDiscoKeyHex, ignoreCase = true)
                    )
                }
                if (expectedRegionId != 0) {
                    assertEquals("[$name] RegionID mismatch", expectedRegionId, parsed.derpRegionId)
                }
                assertEquals("[$name] HasEmbeddedRegion mismatch", hasEmbeddedRegion, parsed.hasEmbeddedRegion)
            }

            if (expectedClassification == "LEGACY_REISSUE_REQUIRED") {
                assertFalse("[$name] Legacy token must NEVER be connectable", parsed.isConnectable)
                val validationState = TokenParser.validate(token)
                assertTrue("[$name] Validation state must be LegacyReissueRequired", validationState is TokenValidationState.LegacyReissueRequired)
            }

            if (expectedClassification == "EXPIRED") {
                assertFalse("[$name] Expired token must NEVER be connectable", parsed.isConnectable)
                assertTrue("[$name] isExpired must be true", parsed.isExpired)
                val validationState = TokenParser.validate(token)
                assertTrue("[$name] Validation state must be Expired", validationState is TokenValidationState.Expired)
            }

            if (expectedClassification == "INVALID") {
                assertFalse("[$name] Invalid token must NEVER be connectable", parsed.isConnectable)
                if (expectedErrorCode.isNotEmpty()) {
                    assertEquals("[$name] ErrorCode mismatch", expectedErrorCode, parsed.errorCode.name)
                }
                val validationState = TokenParser.validate(token)
                assertTrue("[$name] Validation state must be Invalid", validationState is TokenValidationState.Invalid)
            }
        }
    }

    @Test
    fun testDerpRegionDisplayNames() {
        val cases = listOf(
            1 to "NYC (Region 1)",
            2 to "SFO (Region 2)",
            3 to "Singapore (Region 3)",
            4 to "Frankfurt (Region 4)",
            6 to "London (Region 6)",
            7 to "Tokyo (Region 7)",
            8 to "Toronto (Region 8)",
            302 to "San Francisco (Region 302)"
        )

        for ((regionId, expectedName) in cases) {
            val parsed = com.tailcat.vpn.core.token.ParsedToken(
                rawToken = "tc...",
                classification = TokenClassification.VALID_OFFICIAL_SHORT,
                derpRegionId = regionId
            )
            assertEquals(expectedName, parsed.regionDisplayName)
        }
    }
}
