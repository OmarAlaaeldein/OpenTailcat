package com.tailcat.vpn.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportSocketProtectTest {

    @Test
    fun listCandidateFds_parsesNumericNamesAndExcludes() {
        val fds = TransportSocketProtect.listCandidateFds(
            arrayOf("0", "1", "2", "45", "not-a-fd", "45", "-1"),
            exclude = setOf(1, 45)
        )
        assertEquals(listOf(0, 2), fds)
    }

    @Test
    fun listCandidateFds_nullOrEmpty() {
        assertTrue(TransportSocketProtect.listCandidateFds(null).isEmpty())
        assertTrue(TransportSocketProtect.listCandidateFds(emptyArray()).isEmpty())
    }

    @Test
    fun protectAll_countsSuccessesAndSwallowsFailures() {
        val protected = TransportSocketProtect.protectAll(listOf(3, 4, 5)) { fd ->
            when (fd) {
                3 -> true
                4 -> error("boom")
                else -> false
            }
        }
        assertEquals(1, protected)
    }
}
