package com.tailcat.vpn

import com.tailcat.vpn.core.token.TokenParser
import org.json.JSONArray
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kotlin half of the differential check. core-engine/testdata/token_differential.json
 * holds Go's verdict on thousands of mutated tokens (TestTokenDifferentialCorpus);
 * the Kotlin parser must agree on every one.
 */
class TokenDifferentialTest {

    @Test
    fun kotlinParserAgreesWithGoOnMutatedTokens() {
        val stream = javaClass.classLoader?.getResourceAsStream("token_differential.json")
        assertNotNull("token_differential.json not found on the test classpath", stream)
        val cases = JSONArray(stream!!.bufferedReader().use { it.readText() })
        assertTrue("differential corpus is unexpectedly small", cases.length() > 1000)

        val mismatches = mutableListOf<String>()
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val token = c.getString("token")
            val parsed = TokenParser.parse(token)

            val wantClass = c.getString("classification")
            val wantCode = c.optString("errorCode", "").ifEmpty { "ERR_NONE" }
            val got = buildList {
                if (parsed.classification.name != wantClass) {
                    add("classification ${parsed.classification.name} != $wantClass")
                }
                if (parsed.errorCode.name != wantCode) {
                    add("errorCode ${parsed.errorCode.name} != $wantCode")
                }
                if (wantClass.startsWith("VALID_")) {
                    val node = c.getString("nodeKeyHex")
                    val disco = c.getString("discoKeyHex")
                    if (!node.equals(parsed.serverPublicKeyHex, ignoreCase = true)) add("node key differs")
                    if (!disco.equals(parsed.serverDiscoKeyHex, ignoreCase = true)) add("disco key differs")
                    if (c.getInt("regionId") != (parsed.derpRegionId ?: 0)) {
                        add("regionId ${parsed.derpRegionId} != ${c.getInt("regionId")}")
                    }
                }
            }
            if (got.isNotEmpty()) mismatches += "#$i ${token.take(48)}…: ${got.joinToString("; ")}"
        }
        if (mismatches.isNotEmpty()) {
            runCatching {
                java.io.File("build/token-differential-mismatches.txt")
                    .writeText(mismatches.joinToString("\n", postfix = "\n"))
            }
        }
        assertTrue(
            "${mismatches.size} of ${cases.length()} tokens parse differently in Kotlin and Go " +
                "(full list: app/build/token-differential-mismatches.txt):\n" +
                mismatches.take(25).joinToString("\n"),
            mismatches.isEmpty()
        )
    }
}
