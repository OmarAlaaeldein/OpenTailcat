package com.tailcat.vpn.core.update

import com.tailcat.vpn.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches the latest GitHub release with system TLS (not PinnedHttps —
 * those pins are for Cloudflare speed-test endpoints only).
 */
object GitHubReleaseClient {

    private const val LATEST_URL =
        "https://api.github.com/repos/OmarAlaaeldein/OpenTailcat/releases/latest"
    private const val TIMEOUT_MS = 8_000

    suspend fun fetchLatest(): Result<LatestRelease> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(LATEST_URL).openConnection() as HttpURLConnection)
            try {
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.requestMethod = "GET"
                connection.useCaches = false
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                connection.setRequestProperty(
                    "User-Agent",
                    "OpenTailcat-Android/${BuildConfig.VERSION_NAME}"
                )
                val code = connection.responseCode
                check(code in 200..299) {
                    when (code) {
                        403, 429 -> "GitHub rate limit or blocked (HTTP $code)"
                        404 -> "No published release found"
                        else -> "GitHub returned HTTP $code"
                    }
                }
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                LatestRelease.parse(body)
            } finally {
                connection.disconnect()
            }
        }
    }
}
