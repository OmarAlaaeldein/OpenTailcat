package com.tailcat.vpn.ui.screens.speedtest

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailcat.vpn.core.speedtest.SpeedTestStage
import com.tailcat.vpn.ui.screens.speedtest.components.SpeedometerGauge
import com.tailcat.vpn.ui.theme.AccentCyan
import com.tailcat.vpn.ui.theme.BgDark
import com.tailcat.vpn.ui.theme.BorderSubtle
import com.tailcat.vpn.ui.theme.EmeraldConnected
import com.tailcat.vpn.ui.theme.RedDegraded
import com.tailcat.vpn.ui.theme.SurfaceDark
import com.tailcat.vpn.ui.theme.SurfaceElevated
import com.tailcat.vpn.ui.theme.TextPrimary
import com.tailcat.vpn.ui.theme.TextSecondary
import com.tailcat.vpn.ui.theme.VioletDerp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedTestScreen(
    viewModel: SpeedTestViewModel = viewModel(),
    onNavigateBack: () -> Unit = {}
) {
    val testState by viewModel.testState.collectAsState()
    val isTesting = testState.stage in listOf(
        SpeedTestStage.MEASURING_PING,
        SpeedTestStage.TESTING_DOWNLOAD,
        SpeedTestStage.TESTING_UPLOAD
    )

    Scaffold(
        containerColor = BgDark,
        topBar = {
            TopAppBar(
                title = { Text("Network Benchmark", color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimary
                        )
                    }
                },
                actions = {
                    if (testState.stage == SpeedTestStage.COMPLETED) {
                        IconButton(onClick = { viewModel.reset() }) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Reset",
                                tint = AccentCyan
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgDark)
            )
        }
    ) { innerPadding ->
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp)
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            // Direct Network Benchmark Disclaimer Banner
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(SurfaceElevated)
                    .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = if (testState.viaGateway) {
                        "Gateway tunnel benchmark: traffic uses Tailcat DialTCP through the connected gateway."
                    } else {
                        "Physical-network benchmark: the app UID bypasses the VPN, so this is the direct device path."
                    },
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Speedometer Arc Dial
            SpeedometerGauge(
                currentSpeedMbps = testState.currentSpeedGauge,
                stage = testState.stage
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Progress Bar
            if (isTesting) {
                LinearProgressIndicator(
                    progress = { testState.progress },
                    color = AccentCyan,
                    trackColor = SurfaceElevated,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                )
            } else {
                Spacer(modifier = Modifier.height(4.dp))
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 4-Card Telemetry Grid (Ping, Jitter, Download, Upload)
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    MetricTile(
                        title = "PING (RTT)",
                        value = if (testState.pingMs > 0) "${testState.pingMs} ms" else "--",
                        icon = Icons.Default.Timer,
                        tint = AccentCyan,
                        isActive = testState.stage == SpeedTestStage.MEASURING_PING,
                        modifier = Modifier.weight(1f)
                    )
                    MetricTile(
                        title = "JITTER",
                        value = if (testState.jitterMs > 0) "±${testState.jitterMs} ms" else "--",
                        icon = Icons.Default.Waves,
                        tint = AccentCyan,
                        isActive = testState.stage == SpeedTestStage.MEASURING_PING,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    MetricTile(
                        title = "DOWNLOAD",
                        value = if (testState.downloadMbps > 0) String.format(java.util.Locale.US, "%.1f Mbps", testState.downloadMbps) else "--",
                        icon = Icons.Default.ArrowDownward,
                        tint = EmeraldConnected,
                        isActive = testState.stage == SpeedTestStage.TESTING_DOWNLOAD,
                        modifier = Modifier.weight(1f)
                    )
                    MetricTile(
                        title = "UPLOAD",
                        value = if (testState.uploadMbps > 0) String.format(java.util.Locale.US, "%.1f Mbps", testState.uploadMbps) else "--",
                        icon = Icons.Default.ArrowUpward,
                        tint = VioletDerp,
                        isActive = testState.stage == SpeedTestStage.TESTING_UPLOAD,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            if (testState.stage == SpeedTestStage.FAILED) {
                Text(
                    text = testState.errorMessage ?: "The benchmark could not complete",
                    style = MaterialTheme.typography.bodyMedium.copy(color = RedDegraded),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                )
            }

            // Start / Retest Action Button
            Button(
                onClick = { viewModel.startSpeedTest() },
                enabled = !isTesting,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentCyan,
                    disabledContainerColor = SurfaceElevated
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
            ) {
                Icon(
                    imageVector = if (testState.stage == SpeedTestStage.COMPLETED) Icons.Default.Refresh else Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = BgDark
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = when (testState.stage) {
                        SpeedTestStage.IDLE -> "START SPEED TEST"
                        SpeedTestStage.COMPLETED -> "TEST AGAIN"
                        SpeedTestStage.FAILED -> "RETRY TEST"
                        else -> "BENCHMARKING..."
                    },
                    style = MaterialTheme.typography.titleMedium.copy(
                        color = BgDark,
                        fontSize = 15.sp
                    )
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun MetricTile(
    title: String,
    value: String,
    icon: ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    val borderColor = if (isActive) tint else BorderSubtle

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceDark)
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge.copy(
                    color = TextPrimary,
                    fontSize = 18.sp
                )
            )
        }
    }
}
