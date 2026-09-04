package com.tailcat.vpn.core.speedtest

import com.tailcat.vpn.TailcatApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import com.tailcat.vpn.core.tls.PinnedHttps
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.abs
import kotlin.math.round

class SpeedTestEngine {

    private val _testState = MutableStateFlow(SpeedTestResult())
    val testState: StateFlow<SpeedTestResult> = _testState.asStateFlow()

    suspend fun runSpeedTest(viaGateway: Boolean = false) = withContext(Dispatchers.IO) {
        _testState.value = SpeedTestResult(
            stage = SpeedTestStage.MEASURING_PING,
            progress = 0.05f,
            viaGateway = viaGateway
        )

        try {
            // Stage 1: Ping & Jitter Measurement
            val pingSamples = mutableListOf<Long>()
            for (i in 1..5) {
                if (!isActive) return@withContext
                val ping = if (viaGateway) {
                    runCatching { TailcatApplication.instance.tunnelEngine.measureTunnelPingMs() }.getOrDefault(-1L)
                } else {
                    measureSinglePing()
                }
                if (ping > 0) {
                    pingSamples.add(ping)
                }
                _testState.value = _testState.value.copy(
                    pingMs = if (pingSamples.isNotEmpty()) pingSamples.average().toLong() else 0L,
                    progress = 0.05f + (i * 0.03f)
                )
                delay(150)
            }

            check(pingSamples.isNotEmpty()) { "The latency endpoint did not respond" }
            val finalPing = pingSamples.average().toLong()
            val jitter = if (pingSamples.size > 1) {
                val diffs = pingSamples.zipWithNext { a, b -> abs(a - b) }
                diffs.average().toLong()
            } else {
                0L
            }

            _testState.value = _testState.value.copy(
                stage = SpeedTestStage.TESTING_DOWNLOAD,
                pingMs = finalPing,
                jitterMs = jitter,
                progress = 0.20f
            )

            val downloadSpeed = if (viaGateway) {
                val mbps = TailcatApplication.instance.tunnelEngine.measureTunnelDownloadMbps()
                check(mbps > 0.0) { "Download test returned no data" }
                _testState.value = _testState.value.copy(
                    downloadMbps = mbps,
                    currentSpeedGauge = mbps,
                    progress = 0.60f
                )
                mbps
            } else measureDownloadSpeed { currentMbps, stageProgress ->
                _testState.value = _testState.value.copy(
                    downloadMbps = currentMbps,
                    currentSpeedGauge = currentMbps,
                    progress = 0.20f + (stageProgress * 0.40f)
                )
            }

            _testState.value = _testState.value.copy(
                stage = SpeedTestStage.TESTING_UPLOAD,
                downloadMbps = downloadSpeed,
                currentSpeedGauge = 0.0,
                progress = 0.60f
            )

            val uploadSpeed = if (viaGateway) {
                val mbps = TailcatApplication.instance.tunnelEngine.measureTunnelUploadMbps()
                check(mbps > 0.0) { "Upload test returned no data" }
                mbps
            } else measureUploadSpeed { currentMbps, stageProgress ->
                _testState.value = _testState.value.copy(
                    uploadMbps = currentMbps,
                    currentSpeedGauge = currentMbps,
                    progress = 0.60f + (stageProgress * 0.40f)
                )
            }

            // Stage 4: Completed
            _testState.value = _testState.value.copy(
                stage = SpeedTestStage.COMPLETED,
                downloadMbps = downloadSpeed,
                uploadMbps = uploadSpeed,
                currentSpeedGauge = downloadSpeed,
                progress = 1.0f
            )

        } catch (error: CancellationException) {
            throw error
        } catch (e: Exception) {
            _testState.value = _testState.value.copy(
                stage = SpeedTestStage.FAILED,
                errorMessage = e.message ?: "Speed test encountered an error"
            )
        }
    }

    private fun measureSinglePing(): Long {
        return try {
            val startTime = System.nanoTime()
            val conn = PinnedHttps.open("https://1.1.1.1/cdn-cgi/trace").apply {
                connectTimeout = 2500
                readTimeout = 2500
                requestMethod = "GET"
                useCaches = false
            }
            try {
                check(conn.responseCode in 200..299) { "Latency endpoint returned HTTP ${conn.responseCode}" }
                conn.inputStream.use { stream ->
                    val buffer = ByteArray(1024)
                    while (stream.read(buffer) != -1) Unit
                }
                ((System.nanoTime() - startTime) / 1_000_000L).coerceAtLeast(1L)
            } finally {
                conn.disconnect()
            }
        } catch (error: Exception) {
            -1L
        }
    }

    private suspend fun measureDownloadSpeed(onProgress: (Double, Float) -> Unit): Double {
        val testDurationMs = 5000L
        val startTime = System.currentTimeMillis()
        var totalBytesRead = 0L
        var lastReportedSpeed = 0.0

        try {
            val conn = PinnedHttps.open("https://speed.cloudflare.com/__down?bytes=25000000").apply {
                connectTimeout = 4000
                readTimeout = 4000
                requestMethod = "GET"
                useCaches = false
            }

            val buffer = ByteArray(32768)
            check(conn.responseCode in 200..299) { "Download endpoint returned HTTP ${conn.responseCode}" }
            val stream: InputStream = conn.inputStream

            while (System.currentTimeMillis() - startTime < testDurationMs) {
                val bytes = stream.read(buffer)
                if (bytes == -1) break
                totalBytesRead += bytes

                val elapsedSec = (System.currentTimeMillis() - startTime) / 1000.0
                if (elapsedSec > 0.3) {
                    val rawMbps = (totalBytesRead * 8.0) / (elapsedSec * 1_000_000.0)
                    val cleanMbps = round(rawMbps * 10.0) / 10.0
                    lastReportedSpeed = cleanMbps
                    val stageProgress = ((System.currentTimeMillis() - startTime) / testDurationMs.toFloat()).coerceIn(0f, 1f)
                    onProgress(cleanMbps, stageProgress)
                }
            }
            stream.close()
            conn.disconnect()
        } catch (error: CancellationException) {
            throw error
        } catch (e: Exception) {
            throw IllegalStateException("Download test failed: ${e.message ?: "network error"}", e)
        }

        check(lastReportedSpeed > 0.0) { "Download test returned no data" }
        return round(lastReportedSpeed * 10.0) / 10.0
    }

    private suspend fun measureUploadSpeed(onProgress: (Double, Float) -> Unit): Double {
        val testDurationMs = 4000L
        val startTime = System.currentTimeMillis()
        var totalBytesSent = 0L
        var lastReportedSpeed = 0.0

        try {
            val conn = PinnedHttps.open("https://speed.cloudflare.com/__up").apply {
                connectTimeout = 4000
                readTimeout = 4000
                requestMethod = "POST"
                doOutput = true
                useCaches = false
                setChunkedStreamingMode(16384)
            }

            val buffer = ByteArray(16384)
            val stream: OutputStream = conn.outputStream

            while (System.currentTimeMillis() - startTime < testDurationMs) {
                stream.write(buffer)
                totalBytesSent += buffer.size

                val elapsedSec = (System.currentTimeMillis() - startTime) / 1000.0
                if (elapsedSec > 0.3) {
                    val rawMbps = (totalBytesSent * 8.0) / (elapsedSec * 1_000_000.0)
                    val cleanMbps = round(rawMbps * 10.0) / 10.0
                    lastReportedSpeed = cleanMbps
                    val stageProgress = ((System.currentTimeMillis() - startTime) / testDurationMs.toFloat()).coerceIn(0f, 1f)
                    onProgress(cleanMbps, stageProgress)
                }
            }
            stream.flush()
            stream.close()
            check(conn.responseCode in 200..299) { "Upload endpoint returned HTTP ${conn.responseCode}" }
            conn.inputStream?.close()
            conn.disconnect()
        } catch (error: CancellationException) {
            throw error
        } catch (e: Exception) {
            throw IllegalStateException("Upload test failed: ${e.message ?: "network error"}", e)
        }

        check(lastReportedSpeed > 0.0) { "Upload test returned no data" }
        return round(lastReportedSpeed * 10.0) / 10.0
    }

    fun reset() {
        _testState.value = SpeedTestResult()
    }
}
