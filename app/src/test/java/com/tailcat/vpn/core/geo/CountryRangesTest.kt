package com.tailcat.vpn.core.geo

import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CountryRangesTest {

    private fun ranges(text: String) = CountryRanges { ByteArrayInputStream(text.toByteArray(Charsets.US_ASCII)) }

    private val sample = """
        # a comment, then a blank line

        AD 2.152.216.0/21 46.172.224.0/19
        CN 1.0.1.0/24 1.0.2.0/23 2001:250::/30
        KP 175.45.176.0/22
        XX 9.9.9.0/24
        EU 9.9.8.0/24
        ZZ 1.1.1.0/24
    """.trimIndent()

    @Test
    fun prefixesForJoinsTheSelectedCountriesInFileOrder() {
        val got = ranges(sample).prefixesFor(setOf("KP", "CN"))
        assertEquals("1.0.1.0/24 1.0.2.0/23 2001:250::/30 175.45.176.0/22", got)
    }

    @Test
    fun anEmptySelectionNeverOpensTheData() {
        val neverOpened = CountryRanges { error("the data must not be opened for an empty selection") }
        assertEquals("", neverOpened.prefixesFor(emptySet()))
    }

    @Test
    fun aCodeWithNoDataContributesNothing() {
        assertEquals("", ranges(sample).prefixesFor(setOf("QQ")))
        assertEquals("175.45.176.0/22", ranges(sample).prefixesFor(setOf("KP", "QQ")))
    }

    @Test
    fun aCountryLineWithoutPrefixesContributesNothing() {
        val data = "SE\nNO 10.0.0.0/8\n"
        assertEquals("10.0.0.0/8", ranges(data).prefixesFor(setOf("SE", "NO")))
        assertEquals(listOf("NO"), ranges(data).countries(Locale.ENGLISH).map { it.code })
    }

    @Test
    fun countriesListsOnlyRealIsoCountriesSortedByName() {
        val list = ranges(sample).countries(Locale.ENGLISH)
        // XX, EU and ZZ are not ISO 3166-1 countries and cannot be picked.
        assertEquals(listOf("AD", "CN", "KP"), list.map { it.code })
        assertEquals("Andorra", list[0].name)
        assertEquals("China", list[1].name)
        assertEquals(listOf(2, 3, 1), list.map { it.prefixCount })
    }

    @Test
    fun theFlagIsThePairOfRegionalIndicatorSymbols() {
        assertEquals("🇨🇳", CountryInfo("CN", "China", 1).flag)
    }

    @Test
    fun aMalformedLineFailsClosedEvenWhenItIsNotSelected() {
        for (bad in listOf("cn 1.0.0.0/24", "CNX 1.0.0.0/24", "C 1.0.0.0/24", "12 x", "CN\t1.0.0.0/24")) {
            val data = "# header\nAD 2.0.0.0/8\n$bad\n"
            val countries = assertThrows(CountryRangesException::class.java) { ranges(data).countries() }
            assertTrue("names the line: ${countries.message}", countries.message!!.contains("line 3"))
            assertThrows(CountryRangesException::class.java) { ranges(data).prefixesFor(setOf("AD")) }
        }
    }

    @Test
    fun unreadableDataIsACountryRangesException() {
        val broken = CountryRanges { throw FileNotFoundException("country_ranges.txt") }
        assertThrows(CountryRangesException::class.java) { broken.countries() }
        assertThrows(CountryRangesException::class.java) { broken.prefixesFor(setOf("CN")) }
        // The exception is an IOException, so existing IO error handling still sees it.
        assertTrue(IOException::class.java.isAssignableFrom(CountryRangesException::class.java))
    }
}
