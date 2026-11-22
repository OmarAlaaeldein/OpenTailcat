package com.tailcat.vpn.core.ip

import com.tailcat.vpn.BuildConfig
import com.tailcat.vpn.core.model.EgressInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import com.tailcat.vpn.core.tls.PinnedHttps
import java.io.ByteArrayOutputStream
import java.net.InetAddress

/**
 * Resolves the app process' public IP over the device's current routes. The
 * app does not exclude its own UID, so while the VPN interface is up this
 * request enters the TUN; it is never the gateway egress audit.
 */
class IpAuditor {

    private val _egressInfo = MutableStateFlow(EgressInfo())
    val egressInfo: StateFlow<EgressInfo> = _egressInfo.asStateFlow()

    suspend fun fetchCurrentEgress() = withContext(Dispatchers.IO) {
        _egressInfo.value = _egressInfo.value.copy(isChecking = true)

        val result = fetchCloudflareTrace()
        _egressInfo.value = result ?: EgressInfo(
            ip = "Unavailable",
            isChecking = false
        )
    }

    private fun fetchCloudflareTrace(): EgressInfo? = runCatching {
        request("https://1.1.1.1/cdn-cgi/trace").let { body ->
            val fields = body.lineSequence()
                .mapNotNull { line ->
                    val separator = line.indexOf('=')
                    if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
                }
                .toMap()
            val ip = fields["ip"]?.trim()?.takeIf { isValidIpLiteral(it) }
                ?: error("Cloudflare response did not contain a valid IP")
            EgressInfo(
                ip = ip,
                country = fields["loc"]?.trim()?.takeIf { it.isNotBlank() && it.length <= 16 },
                isChecking = false
            )
        }
    }.getOrNull()

    private fun isValidIpLiteral(raw: String): Boolean {
        if (raw.isEmpty() || raw.length > 64 || raw.any { it.isWhitespace() }) return false
        val isV4 = raw.count { it == '.' } == 3 && raw.all { it.isDigit() || it == '.' }
        val isV6 = raw.contains(':') && raw.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }
        if (!isV4 && !isV6) return false
        return runCatching { InetAddress.getByName(raw) }.isSuccess
    }

    private fun request(endpoint: String): String {
        val connection = PinnedHttps.open(endpoint)
        return try {
            connection.connectTimeout = REQUEST_TIMEOUT_MS
            connection.readTimeout = REQUEST_TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.useCaches = false
            connection.setRequestProperty(
                "User-Agent",
                "OpenTailcat-Android/${BuildConfig.VERSION_NAME}"
            )
            check(connection.responseCode in 200..299) {
                "Endpoint returned HTTP ${connection.responseCode}"
            }
            connection.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(2048)
                while (out.size() < MAX_BODY_BYTES) {
                    val toRead = minOf(buf.size, MAX_BODY_BYTES - out.size())
                    val n = input.read(buf, 0, toRead)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                }
                out.toString(Charsets.UTF_8.name())
            }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val REQUEST_TIMEOUT_MS = 4_000
        private const val MAX_BODY_BYTES = 16 * 1024
    }
}
