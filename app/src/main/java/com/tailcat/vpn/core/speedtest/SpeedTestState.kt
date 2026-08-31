package com.tailcat.vpn.core.speedtest

enum class SpeedTestStage {
    IDLE,
    MEASURING_PING,
    TESTING_DOWNLOAD,
    TESTING_UPLOAD,
    COMPLETED,
    FAILED
}

data class SpeedTestResult(
    val stage: SpeedTestStage = SpeedTestStage.IDLE,
    val pingMs: Long = 0,
    val jitterMs: Long = 0,
    val downloadMbps: Double = 0.0,
    val uploadMbps: Double = 0.0,
    val progress: Float = 0f, // 0.0 to 1.0
    val currentSpeedGauge: Double = 0.0, // Real-time Mbps for needle
    val errorMessage: String? = null
)
