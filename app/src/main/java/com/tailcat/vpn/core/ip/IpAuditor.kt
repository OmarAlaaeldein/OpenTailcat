package com.tailcat.vpn.core.ip

import com.tailcat.vpn.core.model.EgressInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Resolves the app process' public IP. Tailcat currently excludes its own UID from the TUN. */
class IpAuditor {

    private val _egressInfo = MutableStateFlow(EgressInfo())
    val egressInfo: StateFlow<EgressInfo> = _egressInfo.asStateFlow()

    suspend fun fetchCurrentEgress() = withContext(Dispatchers.IO) {
        _egressInfo.value = _egressInfo.value.copy(isChecking = true)

        val result = fetchCloudflareTrace() ?: fetchIpify()
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
            val ip = fields["ip"]?.takeIf { it.isNotBlank() }
                ?: error("Cloudflare response did not contain an IP")
            EgressInfo(
                ip = ip,
                country = fields["loc"]?.takeIf { it.isNotBlank() },
                isChecking = false
            )
        }
    }.getOrNull()

    private fun fetchIpify(): EgressInfo? = runCatching {
        val json = JSONObject(request("https://api.ipify.org?format=json"))
        val ip = json.optString("ip").takeIf { it.isNotBlank() }
            ?: error("ipify response did not contain an IP")
        EgressInfo(
            ip = ip,
            isChecking = false
        )
    }.getOrNull()

    private fun request(endpoint: String): String {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = REQUEST_TIMEOUT_MS
            connection.readTimeout = REQUEST_TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", "OpenTailcat-Android/1.2.0")
            check(connection.responseCode in 200..299) {
                "Endpoint returned HTTP ${connection.responseCode}"
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val REQUEST_TIMEOUT_MS = 4_000
    }
}
