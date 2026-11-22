package com.tailcat.vpn.core.dns

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

    fun isValid(ip: String?): Boolean {
        return validate(ip) is DnsValidationResult.Valid
    }

    /**
     * Returns why [resolver] cannot work through this gateway, or null when
     * it can. An IPv6 resolver needs gateway IPv6 internet ([ipv6Egress],
     * measured by prepare); without it the engine refuses every lookup and
     * the device would have no DNS while the VPN shows connected.
     */
    fun gatewayRejection(resolver: DnsValidationResult.Valid, ipv6Egress: Boolean): String? =
        if (resolver.isIpv6 && !ipv6Egress) {
            "DNS server ${resolver.ip} is IPv6, but this gateway has no IPv6 internet. " +
                "Use an IPv4 DNS server such as 1.1.1.1."
        } else {
            null
        }

    fun validate(rawInput: String?): DnsValidationResult {
        if (rawInput.isNullOrBlank()) {
            return DnsValidationResult.Invalid("DNS server IP cannot be empty")
        }

        val trimmed = rawInput.trim()

        if (trimmed.contains("/") || trimmed.contains("%") ||
            trimmed.contains("[") || trimmed.contains("]") ||
            trimmed.startsWith("http://") || trimmed.startsWith("https://")
        ) {
            return DnsValidationResult.Invalid("Enter a plain numeric IP without brackets, ports, paths, zones, or CIDR prefixes")
        }

        // Reject explicit host:port forms. Do not treat IPv6 hextets like ::53 as a port.
        if (trimmed.count { it == '.' } == 3 && trimmed.contains(":") &&
            trimmed.substringAfterLast(':').all { it.isDigit() }
        ) {
            return DnsValidationResult.Invalid("Enter a plain numeric IP without ports, paths, zones, or CIDR prefixes")
        }

        // Fast reject for domain names / hostnames containing alphabetical characters other than hex
        val hasLetters = trimmed.any { it.isLetter() }
        val isPotentiallyIpv6 = trimmed.contains(":")

        if (hasLetters && !isPotentiallyIpv6) {
            return DnsValidationResult.Invalid("Domain names cannot be used as DNS server IPs")
        }

        // Check IPv6 first so IPv4-mapped/embedded IPv6 (e.g. 64:ff9b::1.1.1.1)
        // is not misrouted to IPv4 parsing.
        if (isPotentiallyIpv6) {
            return validateIpv6(trimmed)
        }

        if (trimmed.contains(".")) {
            return validateIpv4(trimmed)
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
            if (!part.all { it.isDigit() }) {
                return DnsValidationResult.Invalid("Non-numeric octet: '$part'")
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

        rejectUnusableIpv4Bytes(octets)?.let { return DnsValidationResult.Invalid(it) }

        return DnsValidationResult.Valid(ip = ipStr, isIpv6 = false)
    }

    private fun rejectUnusableIpv4Bytes(octets: IntArray): String? {
        // Unspecified / "this network" (0.0.0.0/8)
        if (octets[0] == 0) {
            return "0.0.0.0/8 cannot be used as a DNS server"
        }
        // Loopback (127.0.0.0/8)
        if (octets[0] == 127) {
            return "Loopback address cannot be used as a tunnel DNS server"
        }
        // Multicast (224.0.0.0/4) and reserved/broadcast (240.0.0.0/4)
        if (octets[0] in 224..239) {
            return "Multicast address cannot be used as a DNS server"
        }
        if (octets[0] >= 240) {
            return "Reserved IPv4 address cannot be used as a DNS server"
        }
        // Private/LAN ranges (RFC 1918): the live gateway's allowProxyDest
        // rejects private destinations, so a private forced resolver would
        // black out DNS entirely while CONNECTED.
        if (octets[0] == 10) {
            return "Private 10.0.0.0/8 address cannot be used as a tunnel DNS server (gateway rejects private destinations)"
        }
        if (octets[0] == 172 && octets[1] in 16..31) {
            return "Private 172.16.0.0/12 address cannot be used as a tunnel DNS server (gateway rejects private destinations)"
        }
        if (octets[0] == 192 && octets[1] == 168) {
            return "Private 192.168.0.0/16 address cannot be used as a tunnel DNS server (gateway rejects private destinations)"
        }
        // Carrier-grade NAT shared space (100.64.0.0/10, RFC 6598)
        if (octets[0] == 100 && octets[1] in 64..127) {
            return "Carrier-grade NAT 100.64.0.0/10 address cannot be used as a tunnel DNS server"
        }
        // IPv4 link-local (169.254.0.0/16)
        if (octets[0] == 169 && octets[1] == 254) {
            return "Link-local 169.254.0.0/16 address cannot be used as a DNS server"
        }
        // IETF protocol assignments (192.0.0.0/24), documentation (TEST-NET-1/2/3), benchmarking (198.18.0.0/15)
        if (octets[0] == 192 && octets[1] == 0 && (octets[2] == 0 || octets[2] == 2)) {
            return "Special-purpose IPv4 address cannot be used as a DNS server"
        }
        if (octets[0] == 198 && ((octets[1] and 0xFE) == 18 || (octets[1] == 51 && octets[2] == 100))) {
            return "Special-purpose IPv4 address cannot be used as a DNS server"
        }
        if (octets[0] == 203 && octets[1] == 0 && octets[2] == 113) {
            return "Documentation IPv4 address cannot be used as a DNS server"
        }
        return null
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

        if (parsed.isLinkLocalAddress) {
            return DnsValidationResult.Invalid("IPv6 link-local address cannot be used as a DNS server")
        }

        val raw = parsed.address
        if (raw.size != 16) {
            return DnsValidationResult.Invalid("Invalid IPv6 address format")
        }
        // Unique-local (fc00::/7, RFC 4193) and deprecated site-local (fec0::/10)
        if ((raw[0].toInt() and 0xFE) == 0xFC) {
            return DnsValidationResult.Invalid("IPv6 unique-local address cannot be used as a DNS server")
        }
        if (raw[0] == 0xFE.toByte() && (raw[1].toInt() and 0xC0) == 0xC0) {
            return DnsValidationResult.Invalid("IPv6 site-local address cannot be used as a DNS server")
        }
        // Deprecated IPv4-compatible IPv6 ::/96
        if ((0 until 12).all { raw[it] == 0.toByte() }) {
            return DnsValidationResult.Invalid("IPv4-compatible IPv6 address cannot be used as a DNS server")
        }
        // Discard-only 100::/64
        if (raw[0] == 0x01.toByte() && raw[1] == 0x00.toByte() && (2 until 8).all { raw[it] == 0.toByte() }) {
            return DnsValidationResult.Invalid("Discard-only 100::/64 address cannot be used as a DNS server")
        }
        // Documentation 2001:db8::/32
        if (raw[0] == 0x20.toByte() && raw[1] == 0x01.toByte() && raw[2] == 0x0d.toByte() && raw[3] == 0xb8.toByte()) {
            return DnsValidationResult.Invalid("Documentation 2001:db8::/32 address cannot be used as a DNS server")
        }
        // RFC 8215 local-use NAT64 64:ff9b:1::/48
        if (raw[0] == 0x00.toByte() && raw[1] == 0x64.toByte() &&
            raw[2] == 0xff.toByte() && raw[3] == 0x9b.toByte() &&
            raw[4] == 0x00.toByte() && raw[5] == 0x01.toByte()
        ) {
            return DnsValidationResult.Invalid("Local-use NAT64 64:ff9b:1::/48 address cannot be used as a DNS server")
        }
        // Well-known NAT64 64:ff9b::/96: validate the embedded IPv4 address
        if (raw[0] == 0x00.toByte() && raw[1] == 0x64.toByte() &&
            raw[2] == 0xff.toByte() && raw[3] == 0x9b.toByte() &&
            (4 until 12).all { raw[it] == 0.toByte() }
        ) {
            val v4Octets = intArrayOf(
                raw[12].toInt() and 0xff,
                raw[13].toInt() and 0xff,
                raw[14].toInt() and 0xff,
                raw[15].toInt() and 0xff
            )
            rejectUnusableIpv4Bytes(v4Octets)?.let { return DnsValidationResult.Invalid(it) }
        }

        return DnsValidationResult.Valid(ip = ipStr, isIpv6 = true)
    }
}
