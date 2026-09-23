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
 * Streams a release APK into the app cache and verifies SHA-256 when a
 * digest is available from GitHub or the release SHA256SUMS asset.
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

    suspend fun download(
        asset: ReleaseAsset,
        expectedSha256: String?
    ): Result<File> = withContext(Dispatchers.IO) {
        _progress.value = DownloadProgress(active = true, total = asset.size)
        runCatching {
            val dir = File(cacheDir, "updates").apply { mkdirs() }
            val target = File(dir, asset.name)
            val connection = (URL(asset.browserDownloadUrl).openConnection() as HttpURLConnection)
            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.requestMethod = "GET"
                connection.useCaches = false
                connection.instanceFollowRedirects = true
                connection.setRequestProperty(
                    "User-Agent",
                    "OpenTailcat-Android/${com.tailcat.vpn.BuildConfig.VERSION_NAME}"
                )
                check(connection.responseCode in 200..299) {
                    "Download failed: HTTP ${connection.responseCode}"
                }
                val total = connection.contentLengthLong.takeIf { it > 0 } ?: asset.size
                val digest = MessageDigest.getInstance("SHA-256")
                var readTotal = 0L
                connection.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n <= 0) break
                            output.write(buffer, 0, n)
                            digest.update(buffer, 0, n)
                            readTotal += n
                            _progress.value = DownloadProgress(
                                active = true,
                                fraction = if (total > 0) (readTotal.toFloat() / total).coerceIn(0f, 1f) else 0f,
                                bytes = readTotal,
                                total = total
                            )
                        }
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (!expectedSha256.isNullOrBlank()) {
                    val want = expectedSha256.removePrefix("sha256:").trim().lowercase()
                    check(actual.equals(want, ignoreCase = true)) {
                        "APK SHA-256 mismatch — refusing to install"
                    }
                }
                _progress.value = DownloadProgress(
                    active = false,
                    fraction = 1f,
                    bytes = readTotal,
                    total = total,
                    path = target.absolutePath
                )
                target
            } catch (e: Throwable) {
                target.delete()
                _progress.value = DownloadProgress(active = false, error = e.message ?: "Download failed")
                throw e
            } finally {
                connection.disconnect()
            }
        }
    }

    fun reset() {
        _progress.value = DownloadProgress()
    }
}
