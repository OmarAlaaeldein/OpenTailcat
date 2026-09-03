package com.tailcat.vpn.core.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsValidatorTest {

    @Test
    fun testValidIPv4Addresses() {
        val validIps = listOf(
            "1.1.1.1",
            "8.8.8.8",
            "9.9.9.9",
            "1.0.0.1",
            "8.8.4.4",
            "149.112.112.112",
            "208.67.222.222",
            "192.168.1.1",
            "10.0.0.1"
        )

        for (ip in validIps) {
            val result = DnsValidator.validate(ip)
            assertTrue("Expected '$ip' to be valid IPv4", result is DnsValidationResult.Valid)
            val valid = result as DnsValidationResult.Valid
            assertEquals(ip, valid.ip)
            assertFalse("Expected IPv4 flag to be false for $ip", valid.isIpv6)
            assertTrue(DnsValidator.isValid(ip))
        }
    }

    @Test
    fun testValidIPv6Addresses() {
        val validIps = listOf(
            "2606:4700:4700::1111",
            "2001:4860:4860::8888",
            "2620:fe::fe",
            "2606:4700:4700::1001",
            "2001:4860:4860::8844"
        )

        for (ip in validIps) {
            val result = DnsValidator.validate(ip)
            assertTrue("Expected '$ip' to be valid IPv6", result is DnsValidationResult.Valid)
            val valid = result as DnsValidationResult.Valid
            assertTrue("Expected IPv6 flag to be true for $ip", valid.isIpv6)
            assertTrue(DnsValidator.isValid(ip))
        }
    }

    @Test
    fun testInvalidIPv4OctetsAndRanges() {
        val invalidIps = listOf(
            "256.1.1.1",
            "1.256.1.1",
            "1.1.256.1",
            "1.1.1.256",
            "1.1.1.1.1",
            "1.1.1",
            "1.1",
            "1",
            "1.1.1.-1",
            "1.1.1.a",
            "1.1.1.300",
            "999.999.999.999"
        )

        for (ip in invalidIps) {
            val result = DnsValidator.validate(ip)
            assertTrue("Expected '$ip' to be rejected", result is DnsValidationResult.Invalid)
            assertFalse(DnsValidator.isValid(ip))
        }
    }

    @Test
    fun testLeadingZeroesRejected() {
        val leadingZeroIps = listOf(
            "01.1.1.1",
            "1.01.1.1",
            "1.1.001.1",
            "192.168.01.1"
        )

        for (ip in leadingZeroIps) {
            val result = DnsValidator.validate(ip)
            assertTrue("Expected leading zero IP '$ip' to be rejected", result is DnsValidationResult.Invalid)
            assertFalse(DnsValidator.isValid(ip))
        }
    }

    @Test
    fun testLoopbackMulticastAndBroadcastRejected() {
        val unusable = listOf(
            "127.0.0.1",
            "127.0.0.53",
            "0.0.0.0",
            "255.255.255.255",
            "224.0.0.1",
            "239.255.255.250",
            "::1",
            "::"
        )

        for (ip in unusable) {
            val result = DnsValidator.validate(ip)
            assertTrue("Expected unusable IP '$ip' to be rejected", result is DnsValidationResult.Invalid)
            assertFalse(DnsValidator.isValid(ip))
        }
    }

    @Test
    fun testHostnamesAndMalformedStringsRejected() {
        val malformed = listOf(
            "",
            "   ",
            "dns.google.com",
            "one.one.one.one",
            "https://1.1.1.1",
            "1.1.1.1:53",
            "1.1.1.1/24",
            "1.1.1.1/32",
            "fe80::1",
            "fe80::1%eth0",
            "fe80::1%lo"
        )

        for (raw in malformed) {
            val result = DnsValidator.validate(raw)
            assertTrue("Expected malformed input '$raw' to be rejected", result is DnsValidationResult.Invalid)
            assertFalse(DnsValidator.isValid(raw))
        }
    }
}
