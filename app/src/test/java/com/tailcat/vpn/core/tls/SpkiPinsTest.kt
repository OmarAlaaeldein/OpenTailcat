package com.tailcat.vpn.core.tls

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpkiPinsTest {

    @Test
    fun matchesOnlyWhenAChainKeyIsPinned() {
        val pinnedKey = byteArrayOf(1, 2, 3)
        val otherKey = byteArrayOf(4, 5, 6)
        val pins = setOf(SpkiPins.pinOf(pinnedKey))

        assertTrue(SpkiPins.matches(listOf(otherKey, pinnedKey), pins))
        assertFalse(SpkiPins.matches(listOf(otherKey), pins))
        assertFalse(SpkiPins.matches(emptyList(), pins))
    }

    @Test
    fun pinOfIsBase64Sha256() {
        // SHA-256 of the empty string.
        assertEquals("47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=", SpkiPins.pinOf(ByteArray(0)))
    }

    @Test
    fun kotlinAndGoPinSetsAreIdentical() {
        val goSource = listOf("../core-engine/tls_pin.go", "core-engine/tls_pin.go")
            .map(::File)
            .firstOrNull { it.isFile }
            ?: error("core-engine/tls_pin.go not found from ${File(".").absolutePath}")
        val goPins = Regex("""\{"([A-Za-z0-9+/]{43}=)",""")
            .findAll(goSource.readText())
            .map { it.groupValues[1] }
            .toSet()
        assertEquals(SpkiPins.PINS, goPins)
    }
}
