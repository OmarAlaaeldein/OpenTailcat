package com.tailcat.vpn.core.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Streams a release APK into the app cache. Refuses assets without a GitHub
 * SHA-256 digest, hosts outside GitHub, and sizes that do not match the
 * release metadata; the file is kept only when the digest matches.
 */
class ApkDownloader(private val cacheDir: File) {

    private val _progress = MutableStateFlow(DownloadProgress())
    val progress: StateFlow<DownloadProgress> = _progress.asStateFlow()

    data class DownloadProgress(
        val active: Boolean = false,
        val fraction: Float = 0f,
        val bytes: Long = 0L,
        val total: Long = -1L,
        val path: String? = null,
        val error: String? = null
    )

    suspend fun download(asset: ReleaseAsset): Result<File> = withContext(Dispatchers.IO) {
        _progress.value = DownloadProgress(active = true, total = asset.size)
        var target: File? = null
        var connection: HttpURLConnection? = null
        runCatching {
            UpdatePolicy.assetRejection(asset)?.let { error(it) }
            val expectedSha256 = checkNotNull(asset.sha256)
            val dir = File(cacheDir, "updates").apply { mkdirs() }
            // Only one downloaded APK is ever kept.
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, asset.name).also { target = it }
            val conn = openFollowingAllowedRedirects(asset.browserDownloadUrl).also { connection = it }
            val total = asset.size
            val digest = MessageDigest.getInstance("SHA-256")
            var readTotal = 0L
            conn.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n <= 0) break
                        readTotal += n
                        check(readTotal <= total) { "Download is larger than the release asset" }
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        _progress.value = DownloadProgress(
                            active = true,
                            fraction = (readTotal.toFloat() / total).coerceIn(0f, 1f),
                            bytes = readTotal,
                            total = total
                        )
                    }
                }
            }
            check(readTotal == total) { "Download ended early" }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            check(actual == expectedSha256) { "APK SHA-256 mismatch — refusing to install" }
            _progress.value = DownloadProgress(
                active = false,
                fraction = 1f,
                bytes = readTotal,
                total = total,
                path = file.absolutePath
            )
            file
        }.onFailure { e ->
            target?.delete()
            _progress.value = DownloadProgress(active = false, error = e.message ?: "Download failed")
        }.also {
            connection?.disconnect()
        }
    }

    /** Follows up to [MAX_REDIRECTS] redirects, each to an allowed GitHub host. */
    private fun openFollowingAllowedRedirects(startUrl: String): HttpURLConnection {
        var url = startUrl
        repeat(MAX_REDIRECTS + 1) {
            check(UpdatePolicy.isAllowedDownloadUrl(url)) { "Download redirected off GitHub" }
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.requestMethod = "GET"
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            connection.setRequestProperty(
                "User-Agent",
                "OpenTailcat-Android/${com.tailcat.vpn.BuildConfig.VERSION_NAME}"
            )
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                checkNotNull(location) { "Download redirect without a location" }
                url = URL(URL(url), location).toString()
                return@repeat
            }
            if (code !in 200..299) {
                connection.disconnect()
                error("Download failed: HTTP $code")
            }
            return connection
        }
        error("Too many download redirects")
    }

    fun reset() {
        _progress.value = DownloadProgress()
    }

    private companion object {
        const val MAX_REDIRECTS = 5
    }
}
