package com.tailcat.vpn.core.ip

import com.tailcat.vpn.core.model.EgressInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class IpAuditor {

    private val _egressInfo = MutableStateFlow(EgressInfo())
    val egressInfo: StateFlow<EgressInfo> = _egressInfo.asStateFlow()

    suspend fun fetchCurrentEgress() = withContext(Dispatchers.IO) {
        _egressInfo.value = _egressInfo.value.copy(isChecking = true)

        try {
            // Attempt 1: ipapi.co (provides IP + Country + City)
            val url = URL("https://ipapi.co/json/")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 4000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Tailcat-Android/1.0")
            }

            if (conn.responseCode == 200) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val response = reader.readText()
                reader.close()

                val json = JSONObject(response)
                val ip = json.optString("ip", "Unknown")
                val country = if (json.has("country_name")) json.getString("country_name") else null
                val city = if (json.has("city")) json.getString("city") else null
                val org = if (json.has("org")) json.getString("org") else null

                _egressInfo.value = EgressInfo(
                    ip = ip,
                    country = country,
                    city = city,
                    isp = org,
                    isChecking = false,
                    lastUpdated = System.currentTimeMillis()
                )
                return@withContext
            }
        } catch (e: Exception) {
            // Fallback to basic ipify
        }

        try {
            // Fallback Attempt 2: api.ipify.org
            val url = URL("https://api.ipify.org?format=json")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 4000
                requestMethod = "GET"
            }

            if (conn.responseCode == 200) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val response = reader.readText()
                reader.close()

                val json = JSONObject(response)
                val ip = json.optString("ip", "Unknown")

                _egressInfo.value = EgressInfo(
                    ip = ip,
                    isChecking = false,
                    lastUpdated = System.currentTimeMillis()
                )
                return@withContext
            }
        } catch (e: Exception) {
            _egressInfo.value = _egressInfo.value.copy(
                ip = "Unavailable",
                isChecking = false
            )
        }
    }

    fun reset() {
        _egressInfo.value = EgressInfo()
    }
}
