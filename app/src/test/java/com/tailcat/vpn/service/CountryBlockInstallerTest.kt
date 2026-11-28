package com.tailcat.vpn.service

import com.tailcat.vpn.core.geo.CountryRanges
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CountryBlockInstallerTest {

    private val ranges = CountryRanges {
        ByteArrayInputStream("CN 1.0.1.0/24 1.0.2.0/23\nKP 175.45.176.0/22\n".toByteArray())
    }

    private class RecordingEngine(private val failure: Throwable? = null) {
        val calls = mutableListOf<Pair<String, String>>()
        fun set(prefixes: String, resolverIp: String) {
            calls += prefixes to resolverIp
            failure?.let { throw it }
        }
    }

    @Test
    fun handsTheSelectedPrefixesAndTheResolverToTheEngine() {
        val engine = RecordingEngine()
        CountryBlockInstaller.install(setOf("CN", "KP"), "1.1.1.1", ranges, engine::set)
        assertEquals(listOf("1.0.1.0/24 1.0.2.0/23 175.45.176.0/22" to "1.1.1.1"), engine.calls)
    }

    @Test
    fun anEmptySelectionStillClearsAnyEarlierList() {
        val engine = RecordingEngine()
        CountryBlockInstaller.install(emptySet(), "1.1.1.1", ranges, engine::set)
        assertEquals(listOf("" to "1.1.1.1"), engine.calls)
    }

    @Test
    fun anEmptySelectionDoesNotNeedTheData() {
        val missing = CountryRanges { throw FileNotFoundException("country_ranges.txt") }
        val engine = RecordingEngine()
        CountryBlockInstaller.install(emptySet(), "1.1.1.1", missing, engine::set)
        assertEquals(1, engine.calls.size)
    }

    @Test
    fun dataThatCannotBeLoadedAbandonsTheStartWithoutCallingTheEngine() {
        val missing = CountryRanges { throw FileNotFoundException("country_ranges.txt") }
        val engine = RecordingEngine()
        val failure = assertThrows(PermanentStartFailure::class.java) {
            CountryBlockInstaller.install(setOf("CN"), "1.1.1.1", missing, engine::set)
        }
        assertTrue(failure.message!!.contains("Blocked-country data"))
        // Calling the engine with an empty list here would silently connect unfiltered.
        assertTrue("the engine must not be called", engine.calls.isEmpty())
    }

    @Test
    fun malformedDataAbandonsTheStartWithoutCallingTheEngine() {
        val corrupt = CountryRanges { ByteArrayInputStream("not a country line\n".toByteArray()) }
        val engine = RecordingEngine()
        assertThrows(PermanentStartFailure::class.java) {
            CountryBlockInstaller.install(setOf("CN"), "1.1.1.1", corrupt, engine::set)
        }
        assertTrue(engine.calls.isEmpty())
    }

    @Test
    fun aListTheEngineRejectsAbandonsTheStartWithItsMessage() {
        val engine = RecordingEngine(IllegalStateException("DNS resolver 1.0.1.9 is inside a blocked country"))
        val failure = assertThrows(PermanentStartFailure::class.java) {
            CountryBlockInstaller.install(setOf("CN"), "1.0.1.9", ranges, engine::set)
        }
        assertEquals("DNS resolver 1.0.1.9 is inside a blocked country", failure.message)
    }
}
