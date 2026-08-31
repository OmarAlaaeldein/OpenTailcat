package com.tailcat.vpn

import co.nstant.`in`.cbor.CborBuilder
import co.nstant.`in`.cbor.CborEncoder
import com.tailcat.vpn.core.token.TokenParser
import com.tailcat.vpn.core.token.TokenValidationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64

class TokenParserTest {

    private fun generateTestToken(
        pubKeyBytes: ByteArray,
        derpRegionId: Int,
        expiresAtUnixSec: Long? = null,
        issuedAtUnixSec: Long? = null
    ): String {
        val baos = ByteArrayOutputStream()
        val mapBuilder = CborBuilder()
            .addMap()
            .put("p", pubKeyBytes)
            .put("r", derpRegionId.toLong())

        if (expiresAtUnixSec != null) {
            mapBuilder.put("exp", expiresAtUnixSec)
        }
        if (issuedAtUnixSec != null) {
            mapBuilder.put("iat", issuedAtUnixSec)
        }

        CborEncoder(baos).encode(mapBuilder.end().build())
        val cborBytes = baos.toByteArray()
        val b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(cborBytes)
        return "tc$b64"
    }

    @Test
    fun testValidTokenParsingAndValidation() {
        val fakeKey = ByteArray(32) { it.toByte() }
        val token = generateTestToken(fakeKey, 1)

        val result = TokenParser.parse(token)
        assertTrue(result.isSuccess)

        val parsed = result.getOrNull()
        assertNotNull(parsed)
        assertEquals(1, parsed!!.derpRegionId)
        assertEquals("NYC (Region 1)", parsed.regionDisplayName)
        assertEquals(32, parsed.serverPublicKeyBytes.size)

        // Test real-time validation helper
        val state = TokenParser.validate(token)
        assertTrue(state is TokenValidationState.Valid)
        val validState = state as TokenValidationState.Valid
        assertEquals("NYC (Region 1)", validState.parsed.regionDisplayName)
    }

    @Test
    fun testExpiredTokenValidation() {
        val fakeKey = ByteArray(32) { it.toByte() }
        // Expired 1 hour ago
        val pastExp = (System.currentTimeMillis() / 1000L) - 3600L
        val token = generateTestToken(fakeKey, 1, expiresAtUnixSec = pastExp)

        val result = TokenParser.parse(token)
        assertTrue(result.isSuccess)
        val parsed = result.getOrThrow()
        assertTrue(parsed.isExpired)

        // Validate that live validator detects expired state
        val state = TokenParser.validate(token)
        assertTrue(state is TokenValidationState.Expired)
        val expiredState = state as TokenValidationState.Expired
        assertNotNull(expiredState.expiredDate)
    }

    @Test
    fun testFutureExpiringTokenValidation() {
        val fakeKey = ByteArray(32) { it.toByte() }
        // Expires in 7 days
        val futureExp = (System.currentTimeMillis() / 1000L) + (7 * 86400L)
        val token = generateTestToken(fakeKey, 4, expiresAtUnixSec = futureExp)

        val state = TokenParser.validate(token)
        assertTrue(state is TokenValidationState.Valid)
        val validState = state as TokenValidationState.Valid
        assertEquals(false, validState.parsed.isExpired)
        assertEquals("Frankfurt (Region 4)", validState.parsed.regionDisplayName)
        assertNotNull(validState.parsed.expirationFormatted)
    }

    @Test
    fun testDerpRegionDisplayNames() {
        val fakeKey = ByteArray(32) { 0x42.toByte() }

        val regions = listOf(
            1 to "NYC (Region 1)",
            2 to "SFO (Region 2)",
            3 to "Singapore (Region 3)",
            4 to "Frankfurt (Region 4)",
            6 to "London (Region 6)",
            7 to "Tokyo (Region 7)",
            8 to "Toronto (Region 8)"
        )

        for ((regionId, expectedName) in regions) {
            val token = generateTestToken(fakeKey, regionId)
            val parsed = TokenParser.parse(token).getOrThrow()
            assertEquals(expectedName, parsed.regionDisplayName)
        }
    }

    @Test
    fun testTokenPrefixValidation() {
        val invalidToken = "wg_invalid_token_12345"
        val result = TokenParser.parse(invalidToken)
        assertTrue(result.isFailure)

        val state = TokenParser.validate(invalidToken)
        assertTrue(state is TokenValidationState.Invalid)
        assertTrue((state as TokenValidationState.Invalid).reason.contains("prefix"))
    }

    @Test
    fun testEmptyTokenValidation() {
        val result = TokenParser.parse("")
        assertTrue(result.isFailure)

        val state = TokenParser.validate("")
        assertTrue(state is TokenValidationState.Empty)
    }

    @Test
    fun testCorruptedBase64Payload() {
        val corruptedToken = "tc!!!invalid_base64_payload$$$"
        val state = TokenParser.validate(corruptedToken)
        assertTrue(state is TokenValidationState.Invalid)
    }

    @Test
    fun testShortKeyRejected() {
        val shortKey = ByteArray(8) { 1 }
        val token = generateTestToken(shortKey, 1)

        val result = TokenParser.parse(token)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("exactly 32 bytes") == true)
    }

    @Test
    fun testOversizedKeyRejected() {
        val token = generateTestToken(ByteArray(33) { 1 }, 1)
        val result = TokenParser.parse(token)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("exactly 32 bytes") == true)
    }

    @Test
    fun testExpirationCannotPredateIssuedAt() {
        val issuedAt = (System.currentTimeMillis() / 1000L) + 3_600L
        val token = generateTestToken(
            pubKeyBytes = ByteArray(32) { 2 },
            derpRegionId = 1,
            expiresAtUnixSec = issuedAt - 1,
            issuedAtUnixSec = issuedAt
        )

        val result = TokenParser.parse(token)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("earlier than issued-at") == true)
    }

    @Test
    fun testRawBytesCannotMasqueradeAsCborMap() {
        val raw = ByteArray(32) { 0x42 }
        val token = "tc" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw)

        assertTrue(TokenParser.parse(token).isFailure)
    }
}
