package com.tailcat.vpn.core.dns

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

sealed class DnsValidationResult {
    data class Valid(val ip: String, val isIpv6: Boolean) : DnsValidationResult()
    data class Invalid(val reason: String) : DnsValidationResult()
}

/**
 * Strict IP address validator for DNS resolvers.
 * Ensures resolver addresses passed to Android VpnService.Builder and
 * native tunnel engines are valid unicast IP addresses.
 */
object DnsValidator {

    private val IPV4_PATTERN = Regex(
        "^((25[0-5]|(2[0-4]|1\\d|[1-9]|)\\d)\\.?\\b){4}$"
    )

    fun isValid(ip: String?): Boolean {
        return validate(ip) is DnsValidationResult.Valid
    }

    fun validate(rawInput: String?): DnsValidationResult {
        if (rawInput.isNullOrBlank()) {
            return DnsValidationResult.Invalid("DNS server IP cannot be empty")
        }

        val trimmed = rawInput.trim()

        if (trimmed.contains("/") || trimmed.contains(":53") || trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return DnsValidationResult.Invalid("Enter a plain numeric IP without ports, paths, or CIDR prefixes")
        }

        // Fast reject for domain names / hostnames containing alphabetical characters other than hex
        val hasLetters = trimmed.any { it.isLetter() }
        val isPotentiallyIpv6 = trimmed.contains(":")

        if (hasLetters && !isPotentiallyIpv6) {
            return DnsValidationResult.Invalid("Domain names cannot be used as DNS server IPs")
        }

        // Try IPv4 parsing first
        if (trimmed.contains(".")) {
            return validateIpv4(trimmed)
        }

        // Try IPv6 parsing
        if (isPotentiallyIpv6) {
            return validateIpv6(trimmed)
        }

        return DnsValidationResult.Invalid("Invalid IP address format")
    }

    private fun validateIpv4(ipStr: String): DnsValidationResult {
        val parts = ipStr.split(".")
        if (parts.size != 4) {
            return DnsValidationResult.Invalid("IPv4 address must contain exactly 4 octets")
        }

        val octets = IntArray(4)
        for (i in 0 until 4) {
            val part = parts[i]
            if (part.isEmpty() || part.length > 3) {
                return DnsValidationResult.Invalid("Invalid octet in IPv4 address: '$part'")
            }
            // Disallow leading zeroes that could be interpreted as octal (e.g. 01)
            if (part.length > 1 && part.startsWith("0")) {
                return DnsValidationResult.Invalid("Leading zeroes are not permitted in IPv4 octets")
            }
            val num = part.toIntOrNull() ?: return DnsValidationResult.Invalid("Non-numeric octet: '$part'")
            if (num !in 0..255) {
                return DnsValidationResult.Invalid("Octet out of range (0-255): $num")
            }
            octets[i] = num
        }

        // Unspecified (0.0.0.0)
        if (octets.all { it == 0 }) {
            return DnsValidationResult.Invalid("0.0.0.0 cannot be used as a DNS server")
        }

        // Loopback (127.0.0.0/8)
        if (octets[0] == 127) {
            return DnsValidationResult.Invalid("Loopback address cannot be used as a tunnel DNS server")
        }

        // Multicast (224.0.0.0/4 -> 224..239)
        if (octets[0] in 224..239) {
            return DnsValidationResult.Invalid("Multicast address cannot be used as a DNS server")
        }

        // Broadcast (255.255.255.255)
        if (octets.all { it == 255 }) {
            return DnsValidationResult.Invalid("Broadcast address cannot be used as a DNS server")
        }

        return DnsValidationResult.Valid(ip = ipStr, isIpv6 = false)
    }

    private fun validateIpv6(ipStr: String): DnsValidationResult {
        val parsed = runCatching {
            val addr = InetAddress.getByName(ipStr)
            if (addr is Inet6Address) addr else null
        }.getOrNull() ?: return DnsValidationResult.Invalid("Invalid IPv6 address format")

        if (parsed.isAnyLocalAddress) {
            return DnsValidationResult.Invalid(":: cannot be used as a DNS server")
        }

        if (parsed.isLoopbackAddress) {
            return DnsValidationResult.Invalid("IPv6 loopback (::1) cannot be used as a tunnel DNS server")
        }

        if (parsed.isMulticastAddress) {
            return DnsValidationResult.Invalid("IPv6 multicast address cannot be used as a DNS server")
        }

        return DnsValidationResult.Valid(ip = ipStr, isIpv6 = true)
    }
}
